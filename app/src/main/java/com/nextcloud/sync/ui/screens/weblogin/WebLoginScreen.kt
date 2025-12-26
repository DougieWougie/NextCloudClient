package com.nextcloud.sync.ui.screens.weblogin

import android.os.Build
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nextcloud.sync.ui.components.AppTopBar
import com.nextcloud.sync.ui.components.ErrorDialog
import com.nextcloud.sync.utils.SafeLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebLoginScreen(
    viewModel: WebLoginViewModel,
    onNavigateToMain: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Handle successful login
    LaunchedEffect(uiState.loginSuccess) {
        if (uiState.loginSuccess) {
            onNavigateToMain()
        }
    }

    // Handle back button in WebView
    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    // Show error dialog
    if (uiState.errorMessage != null) {
        ErrorDialog(
            message = uiState.errorMessage!!,
            onDismiss = {
                viewModel.onEvent(WebLoginEvent.ErrorDismissed)
                onNavigateBack()
            }
        )
    }

    // Clean up WebView on dispose
    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                loadUrl("about:blank")
                clearHistory()
                clearCache(true)
                onPause()
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Login",
                onNavigateBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Instruction text
            Text(
                text = uiState.instructionText,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )

            // Progress indicator for page loading
            if (uiState.isPageLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // WebView
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                if (uiState.loginUrl.isNotEmpty()) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                webView = this

                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true

                                    // Security: Disable file access to prevent file:// URL vulnerabilities
                                    allowFileAccess = false
                                    allowContentAccess = false
                                    setGeolocationEnabled(false)

                                    // Security: Disable file access from file URLs (XSS protection)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                                        @Suppress("DEPRECATION")
                                        allowFileAccessFromFileURLs = false
                                        @Suppress("DEPRECATION")
                                        allowUniversalAccessFromFileURLs = false
                                    }

                                    // Optimize WebView to reduce memory usage
                                    cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                                    @Suppress("DEPRECATION")
                                    databaseEnabled = false
                                    setSupportMultipleWindows(false)

                                    // Disable unused features
                                    @Suppress("DEPRECATION")
                                    saveFormData = false
                                }

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        viewModel.onEvent(WebLoginEvent.PageFinished)
                                    }

                                    // Security: Handle SSL errors - NEVER proceed on SSL errors
                                    @Deprecated("Deprecated in Java")
                                    override fun onReceivedSslError(
                                        view: WebView?,
                                        handler: SslErrorHandler?,
                                        error: android.net.http.SslError?
                                    ) {
                                        // CRITICAL: Always cancel on SSL errors - NEVER call handler.proceed()
                                        handler?.cancel()

                                        SafeLogger.e("WebLoginScreen", "SSL certificate validation failed")

                                        viewModel.onEvent(WebLoginEvent.ErrorDismissed)
                                    }

                                    // Security: Validate URLs before loading
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val url = request?.url?.toString() ?: return true

                                        // Only allow navigation to URLs from the configured server
                                        if (!url.startsWith(uiState.serverUrl)) {
                                            SafeLogger.w("WebLoginScreen", "Blocked navigation to external URL")
                                            return true // Block navigation
                                        }

                                        return false // Allow navigation to server URLs
                                    }

                                    @Deprecated("Deprecated in Java")
                                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                        if (url == null) return true

                                        // Only allow navigation to URLs from the configured server
                                        if (!url.startsWith(uiState.serverUrl)) {
                                            SafeLogger.w("WebLoginScreen", "Blocked navigation to external URL")
                                            return true // Block navigation
                                        }

                                        return false // Allow navigation to server URLs
                                    }
                                }

                                loadUrl(uiState.loginUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Loading overlay
                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}
