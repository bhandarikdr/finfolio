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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    companyName: String,
    scrip: String = "",
    boids: List<BoidEntry>,
    portalUrl: String,
    onResultFound: (BoidEntry, String, Boolean) -> Unit,
    onComplete: () -> Unit
) {
    var currentBoidIndex by remember { mutableIntStateOf(0) }
    var webView: WebView? by remember { mutableStateOf(null) }
    var isCaptchaVisible by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("Initializing portal...") }

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

        @JavascriptInterface
        fun updateStatus(msg: String) {
            loadingMessage = msg
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
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
            
            Spacer(Modifier.width(8.dp))
            
            Text(
                text = loadingMessage,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp)
            )
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
                            injectCheckerScript(view, boids.getOrNull(currentBoidIndex)?.boid, companyName, scrip)
                        }
                    }
                    
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    
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

private fun injectCheckerScript(webView: WebView?, boid: String?, companyName: String, scrip: String = "") {
    if (webView == null || boid == null) return
    
    val script = """
        (function() {
            window.resultReported = false;
            window.captchaNotified = false;

            function applyFinfolioFixes() {
                // 1. Setup global override style (Surgical Centering without Squeezing)
                var style = document.getElementById('finfolio-centering-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'finfolio-centering-style';
                    document.head.appendChild(style);
                }
                style.innerHTML = `
                    html, body {
                        width: 100% !important;
                        min-height: 100% !important;
                        margin: 0 !important;
                        padding: 0 !important;
                        display: block !important;
                        background: #ffffff !important;
                    }
                    /* Remove fixed positioning from main app containers that push content off-screen */
                    app-root, .wrapper, .main-panel, .content, .container-fluid {
                        position: relative !important;
                        left: 0 !important;
                        top: 0 !important;
                        width: 100% !important;
                        height: auto !important;
                        display: block !important;
                        padding: 0 !important;
                        margin: 0 !important;
                    }
                    /* Surgical fix for the main result card */
                    mat-card, .card {
                        position: relative !important;
                        margin: 20px auto !important;
                        left: auto !important;
                        right: auto !important;
                        top: auto !important;
                        width: 96% !important;
                        max-width: 500px !important;
                        display: block !important;
                        box-shadow: 0 4px 20px rgba(0,0,0,0.1) !important;
                        box-sizing: border-box !important;
                    }
                    /* Ensure rows and form elements aren't crushed */
                    .row, .mat-row {
                        display: flex !important;
                        flex-wrap: wrap !important;
                        width: 100% !important;
                        margin: 0 !important;
                    }
                    /* Hide headers/footers */
                    nav, header, footer, .navbar, .footer, app-header, app-footer, .text-center.footer {
                        display: none !important;
                    }
                `;

                // Automated Form Injection
                function triggerInput(el, val) {
                    if (!el || el.value === val) return;
                    var nativeValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, "value").set;
                    if (nativeValueSetter) { nativeValueSetter.call(el, val); } else { el.value = val; }
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }

                var companySelect = document.querySelector('select[name="companyShare"]') || document.querySelector('select');
                if (companySelect && !companySelect.hasAttribute('data-filled')) {
                    var searchName = "${companyName.lowercase().trim()}";
                    var searchScrip = "${scrip.lowercase().trim()}";
                    
                    // Fuzzy match logic
                    function cleanName(n) {
                        return n.toLowerCase()
                            .replace(/limited|ltd|investment|bank|indutries|industries|corp|corporation/g, '')
                            .replace(/[\s.,()-]/g, '')
                            .trim();
                    }
                    
                    var targetClean = cleanName(searchName);
                    var foundId = null;
                    
                    for (var i = 0; i < companySelect.options.length; i++) {
                        var optText = companySelect.options[i].text.toLowerCase();
                        var optValue = companySelect.options[i].value;
                        
                        // Priority 1: Scrip Match (if available)
                        if (searchScrip && optText.includes(searchScrip)) {
                            foundId = optValue; break;
                        }
                        
                        // Priority 2: Fuzzy Name Match
                        var optClean = cleanName(optText);
                        if (optClean.includes(targetClean) || targetClean.includes(optClean)) {
                            foundId = optValue; break;
                        }
                    }

                    if (foundId) {
                        companySelect.value = foundId;
                        companySelect.dispatchEvent(new Event('change', { bubbles: true }));
                        companySelect.setAttribute('data-filled', 'true');
                    }
                }

                var boidInput = document.querySelector('input[name="boid"]') || document.querySelector('input[placeholder*="BOID"]');
                if (boidInput && boidInput.value != '$boid') {
                    triggerInput(boidInput, '$boid');
                }

                // Captcha Detection
                var captchaImg = document.querySelector('img[alt="Captcha"]') || document.querySelector('img[src*="captcha"]');
                if (captchaImg && !window.captchaNotified) {
                    window.AndroidInterface.onCaptchaDetected();
                    window.captchaNotified = true;
                }

                // Results Observer
                if (!window.resultReported) {
                    var elements = document.querySelectorAll('p, span, div, h4');
                    for (var i = 0; i < elements.length; i++) {
                        var txt = elements[i].innerText.toLowerCase();
                        if (txt.includes('congratulation') || txt.includes('alloted') || 
                            txt.includes('sorry') || txt.includes('not alloted')) {
                            
                            var fullMsg = elements[i].innerText.trim();
                            if (fullMsg.length > 5) {
                                var isSuccess = (txt.includes('congratulation') || txt.includes('alloted')) && 
                                                !txt.includes('not alloted') && 
                                                !txt.includes('sorry');
                                window.resultReported = true;
                                window.AndroidInterface.updateStatus("Result detected! Saving...");
                                window.AndroidInterface.postResult('$boid', fullMsg, isSuccess);
                                break;
                            }
                        }
                    }
                }
            }

            applyFinfolioFixes();
            if (!window.finfolioLoop) {
                window.finfolioLoop = setInterval(applyFinfolioFixes, 1500);
            }
        })();
    """.trimIndent()
    
    webView.evaluateJavascript(script, null)
}
