package com.example.ui.components

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.BoidEntry
import kotlinx.coroutines.delay

/**
 * Phase 4.1: Hybrid IPO Result Checker.
 * Refined Aesthetics and Automation fixes.
 */
@Composable
fun HybridIpoResultChecker(
    resultPortalId: Int,
    boids: List<BoidEntry>,
    portalUrl: String,
    onResultFound: (BoidEntry, String, Boolean) -> Unit,
    onComplete: () -> Unit
) {
    var currentBoidIndex by remember { mutableIntStateOf(0) }
    var webView: WebView? by remember { mutableStateOf(null) }
    var isCaptchaVisible by remember { mutableStateOf(false) }

    class WebAppInterface {
        @JavascriptInterface
        fun postResult(boid: String, message: String, success: Boolean) {
            val boidEntry = boids.find { it.boid == boid }
            if (boidEntry != null) {
                onResultFound(boidEntry, message, success)
                // Move to next BOID automatically
                if (currentBoidIndex < boids.size - 1) {
                    currentBoidIndex++
                    isCaptchaVisible = false
                } else {
                    onComplete()
                }
            }
        }

        @JavascriptInterface
        fun onCaptchaDetected() {
            isCaptchaVisible = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Aesthetic Progress Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = boids.getOrNull(currentBoidIndex)?.name ?: "Done",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${currentBoidIndex + 1} / ${boids.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (currentBoidIndex.toFloat()) / boids.size },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                strokeCap = StrokeCap.Round,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer
            )
        }

        // Horizontal Selection Chips (Improved Style)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(boids) { index, boid ->
                val isSelected = index == currentBoidIndex
                SuggestionChip(
                    onClick = { 
                        currentBoidIndex = index
                        isCaptchaVisible = false
                    },
                    label = { 
                        Text(
                            text = boid.name.split(" ").firstOrNull() ?: boid.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        ) 
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        labelColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
        
        if (isCaptchaVisible) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Action Required: Solve CAPTCHA for ${boids.getOrNull(currentBoidIndex)?.name}", 
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    clearCache(true)
                    clearHistory()

                    addJavascriptInterface(WebAppInterface(), "AndroidInterface")
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            injectCheckerScript(view, boids.getOrNull(currentBoidIndex)?.boid, resultPortalId)
                        }
                    }
                    
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    
                    // Restore forced-center visual hacks for CDSC portal
                    settings.defaultFontSize = 14
                    settings.minimumFontSize = 10

                    loadUrl(portalUrl)
                    webView = this
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 8.dp) // Aesthetic padding at bottom
        )
    }

    LaunchedEffect(currentBoidIndex) {
        if (currentBoidIndex >= boids.size) {
            onComplete()
            return@LaunchedEffect
        }
        // Force a fresh reload of the CDSC portal for each BOID to reset Angular state and results
        webView?.loadUrl(portalUrl)
        delay(2000)
    }
}

private fun injectCheckerScript(webView: WebView?, boid: String?, portalId: Int) {
    if (webView == null || boid == null) return
    
    val script = """
        (function() {
            // 1. Hide Distractions
            document.querySelectorAll('nav, header, footer, .navbar, .footer, app-header, app-footer').forEach(el => el.style.display = 'none');

            // 2. Dynamic Centering for all screen sizes
            var mainEl = document.querySelector('form') || document.querySelector('.card') || 
                         document.querySelector('mat-card') || document.querySelector('.container');
            
            if (mainEl) {
                // Ensure parent container is clear for centering
                document.body.style.display = 'flex';
                document.body.style.flexDirection = 'column';
                document.body.style.justifyContent = 'center';
                document.body.style.alignItems = 'center';
                document.body.style.minHeight = '100vh';
                document.body.style.margin = '0';
                document.body.style.padding = '0';
                document.body.style.backgroundColor = '#ffffff';
                
                // Remove potential fixed-top/bottom elements from Angular components
                document.querySelectorAll('.footer, .header, .navbar').forEach(el => el.remove());

                mainEl.style.setProperty('position', 'relative', 'important');
                mainEl.style.setProperty('display', 'block', 'important');
                mainEl.style.setProperty('margin', 'auto', 'important');
                mainEl.style.setProperty('width', '94%', 'important');
                mainEl.style.setProperty('max-width', '450px', 'important');
                mainEl.style.setProperty('box-shadow', '0 4px 12px rgba(0,0,0,0.1)', 'important');
                mainEl.style.setProperty('border', '1px solid #ddd', 'important');
                mainEl.style.setProperty('padding', '24px', 'important');
                mainEl.style.setProperty('border-radius', '16px', 'important');
                mainEl.style.setProperty('background', '#fff', 'important');
            }

            // 3. Automated Form Injection
            function triggerInput(el, val) {
                if (!el) return;
                var nativeValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                if (nativeValueSetter) {
                    nativeValueSetter.call(el, val);
                } else {
                    el.value = val;
                }
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            }

            var companySelect = document.querySelector('select[name="companyShare"]');
            if (companySelect && companySelect.value != '$portalId') {
                companySelect.value = '$portalId';
                companySelect.dispatchEvent(new Event('change', { bubbles: true }));
            }

            var boidInput = document.querySelector('input[name="boid"]');
            if (boidInput && boidInput.value != '$boid') {
                triggerInput(boidInput, '$boid');
            }

            // 4. Captcha Detection
            var captchaImg = document.querySelector('img[alt="Captcha"]');
            if (captchaImg) window.AndroidInterface.onCaptchaDetected();

            // 5. Intelligent Monitoring & State Reset
            if (!window.resultMonitorActive) {
                window.resultMonitorActive = true;
                setInterval(function() {
                    var resultDiv = document.querySelector('.result-message') || document.querySelector('.alert');
                    if (resultDiv && resultDiv.innerText.trim().length > 5) {
                        var msg = resultDiv.innerText;
                        var success = msg.includes('Congratulation') || msg.includes('allotted');
                        
                        // Send result to Android and STOP local monitoring until next page load
                        window.AndroidInterface.postResult('$boid', msg, success);
                        
                        // Visually clear to prevent double-triggering before page reload
                        resultDiv.style.display = 'none';
                        window.resultMonitorActive = false;
                    }
                }, 1000);
            }
        })();
    """.trimIndent()
    
    webView.evaluateJavascript(script, null)
}
