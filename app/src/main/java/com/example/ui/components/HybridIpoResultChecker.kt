package com.example.ui.components

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.example.data.model.BoidEntry
import com.example.data.util.AppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 4.1: Hybrid IPO Result Checker.
 * Uses a WebView to bypass WAF and handle CAPTCHAs.
 */
@Composable
fun HybridIpoResultChecker(
    resultPortalId: Int,
    boids: List<BoidEntry>,
    portalUrl: String,
    onResultFound: (BoidEntry, String, Boolean) -> Unit,
    onComplete: () -> Unit
) {
    var currentBoidIndex by remember { mutableStateOf(0) }
    var webView: WebView? by remember { mutableStateOf(null) }
    val scope = rememberCoroutineScope()
    var isCaptchaVisible by remember { mutableStateOf(false) }

    class WebAppInterface {
        @JavascriptInterface
        fun postResult(boid: String, message: String, success: Boolean) {
            val boidEntry = boids.find { it.boid == boid }
            if (boidEntry != null) {
                onResultFound(boidEntry, message, success)
                // Move to next BOID
                currentBoidIndex++
            }
        }

        @JavascriptInterface
        fun onCaptchaDetected() {
            isCaptchaVisible = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator(
            progress = { (currentBoidIndex.toFloat() / boids.size) },
            modifier = Modifier.fillMaxWidth()
        )
        Text("Checking BOID: ${boids.getOrNull(currentBoidIndex)?.name} (${currentBoidIndex + 1}/${boids.size})",
             modifier = Modifier.padding(8.dp),
             style = MaterialTheme.typography.labelMedium)
        
        if (isCaptchaVisible) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("⚠️ CAPTCHA required. Please enter the code in the view below and click Check.", 
                     color = MaterialTheme.colorScheme.error,
                     style = MaterialTheme.typography.labelSmall,
                     modifier = Modifier.padding(8.dp))
            }
        }

        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    addJavascriptInterface(WebAppInterface(), "AndroidInterface")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // Inject logic to check for results
                            injectCheckerScript(view, boids.getOrNull(currentBoidIndex)?.boid, resultPortalId)
                        }
                    }
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = false // Better for zooming specifically
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.domStorageEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = true

                    loadUrl(portalUrl)
                    webView = this
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
    }

    LaunchedEffect(currentBoidIndex) {
        if (currentBoidIndex >= boids.size) {
            onComplete()
            return@LaunchedEffect
        }
        
        // Wait a bit between checks to avoid rate limiting
        delay(2000)
        webView?.let { 
            injectCheckerScript(it, boids[currentBoidIndex].boid, resultPortalId)
        }
    }
}

private fun injectCheckerScript(webView: WebView?, boid: String?, portalId: Int) {
    if (webView == null || boid == null) return
    
    val script = """
        (function() {
            var boidInput = document.querySelector('input[name="boid"]');
            var companySelect = document.querySelector('select[name="companyShare"]');
            var submitBtn = document.querySelector('button[type="submit"]');
            
            // UX: Hide distracting headers/footers for better focal area
            var navbar = document.querySelector('nav'); if(navbar) navbar.style.display='none';
            var footer = document.querySelector('footer'); if(footer) footer.style.display='none';
            var header = document.querySelector('header'); if(header) header.style.display='none';
            
            // Adjust zoom to show the relevant part of the screen
            document.body.style.zoom = "1.0"; 

            if (companySelect && companySelect.value != '$portalId') {
                companySelect.value = '$portalId';
                companySelect.dispatchEvent(new Event('change'));
            }
                
                if (boidInput.value != '$boid') {
                    boidInput.value = '$boid';
                    boidInput.dispatchEvent(new Event('input'));
                }
                
                // Ensure form remains visible (Auto-scroll to Captcha)
                var capInput = document.querySelector('input[name="captcha"]');
                if (capInput) {
                    capInput.scrollIntoView({behavior: "smooth", block: "center"});
                }

                // Monitor for CAPTCHA image
                var captchaImg = document.querySelector('img[alt="Captcha"]');
                if (captchaImg) {
                    window.AndroidInterface.onCaptchaDetected();
                }

                // Monitor for result
                if (!window.resultMonitorActive) {
                    window.resultMonitorActive = true;
                    var checkInterval = setInterval(function() {
                        var resultDiv = document.querySelector('.result-message');
                        if (resultDiv && resultDiv.innerText.trim().length > 5) {
                            var msg = resultDiv.innerText;
                            var success = msg.includes('Congratulation');
                            window.AndroidInterface.postResult('$boid', msg, success);
                            // Do NOT clear interval, let the next BOID trigger it
                        }
                    }, 1500);
                }
            }
        })();
    """.trimIndent()
    
    webView.evaluateJavascript(script, null)
}
