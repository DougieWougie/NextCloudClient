package com.nextcloud.sync.views.activities

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nextcloud.sync.databinding.ActivityWebLoginBinding
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.database.entities.AccountEntity
import com.nextcloud.sync.models.network.LoginFlowInitResult
import com.nextcloud.sync.models.network.LoginFlowPollResult
import com.nextcloud.sync.models.network.NextcloudLoginFlow
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.utils.EncryptionUtil
import com.nextcloud.sync.utils.SafeLogger
import kotlinx.coroutines.launch

class WebLoginActivity : AppCompatActivity() {
    private var _binding: ActivityWebLoginBinding? = null
    private val binding get() = _binding!!
    private lateinit var accountRepository: AccountRepository
    private lateinit var loginFlow: NextcloudLoginFlow
    private var serverUrl: String = ""
    private var pollToken: String = ""
    private var pollEndpoint: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityWebLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        serverUrl = intent.getStringExtra("server_url") ?: ""

        if (serverUrl.isEmpty()) {
            showError("Invalid server URL")
            finish()
            return
        }

        setupRepository()
        setupWebView()
        setupBackPressedHandler()
        startLoginFlow()
    }

    private fun setupRepository() {
        val db = AppDatabase.getInstance(this)
        accountRepository = AccountRepository(db.accountDao())
    }

    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true

            // Security: Disable file access to prevent file:// URL vulnerabilities
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)

            // Security: Disable file access from file URLs (XSS protection)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
            }

            // Optimize WebView to reduce memory usage and ashmem warnings
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            @Suppress("DEPRECATION")
            databaseEnabled = false
            setSupportMultipleWindows(false)

            // Disable unused features to reduce memory footprint
            @Suppress("DEPRECATION")
            saveFormData = false
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
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

                SafeLogger.e("WebLoginActivity", "SSL certificate validation failed")

                showError("SSL certificate validation failed. The connection is not secure. Please check your server configuration.")
                finish()
            }

            // Security: Validate URLs before loading to prevent navigation to malicious sites
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return true

                // Only allow navigation to URLs from the configured server
                if (!url.startsWith(serverUrl)) {
                    SafeLogger.w("WebLoginActivity", "Blocked navigation to external URL")
                    return true // Block navigation
                }

                return false // Allow navigation to server URLs
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return true

                // Only allow navigation to URLs from the configured server
                if (!url.startsWith(serverUrl)) {
                    SafeLogger.w("WebLoginActivity", "Blocked navigation to external URL")
                    return true // Block navigation
                }

                return false // Allow navigation to server URLs
            }
        }
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (_binding?.webView?.canGoBack() == true) {
                    _binding?.webView?.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun startLoginFlow() {
        binding.progressBar.visibility = View.VISIBLE
        loginFlow = NextcloudLoginFlow(this, serverUrl)

        lifecycleScope.launch {
            when (val result = loginFlow.initLoginFlow()) {
                is LoginFlowInitResult.Success -> {
                    pollToken = result.pollToken
                    pollEndpoint = result.pollEndpoint

                    // Load login page in WebView
                    runOnUiThread {
                        binding.webView.loadUrl(result.loginUrl)
                        binding.textInstructions.text = "Please sign in to your Nextcloud account in the browser below"
                    }

                    // Start polling for credentials
                    pollForCredentials()
                }
                is LoginFlowInitResult.Error -> {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        showError(result.message)
                    }
                }
            }
        }
    }

    private fun pollForCredentials() {
        lifecycleScope.launch {
            runOnUiThread {
                binding.textInstructions.text = "Sign in through the browser below, then we'll automatically connect"
            }

            when (val result = loginFlow.pollForCredentials(pollEndpoint, pollToken)) {
                is LoginFlowPollResult.Success -> {
                    val credentials = result.credentials
                    runOnUiThread {
                        binding.textInstructions.text = "Login successful! Setting up your account..."
                        binding.progressBar.visibility = View.VISIBLE
                    }
                    saveCredentialsAndContinue(credentials)
                }
                is LoginFlowPollResult.Timeout -> {
                    runOnUiThread {
                        showError("Login timeout. Please try again.")
                    }
                }
                is LoginFlowPollResult.Error -> {
                    runOnUiThread {
                        showError("Login failed: ${result.message}\n\nPlease try again or check your server configuration.")
                    }
                }
            }
        }
    }

    private suspend fun saveCredentialsAndContinue(credentials: NextcloudLoginFlow.LoginCredentials) {
        // Deactivate all existing accounts
        accountRepository.deactivateAllAccounts()

        // Encrypt the app password and auth token
        val encryptedPassword = EncryptionUtil.encryptPassword(credentials.appPassword)
        val encryptedAuthToken = EncryptionUtil.encryptPassword(credentials.appPassword)

        // Create new account
        val account = AccountEntity(
            serverUrl = credentials.serverUrl,
            username = credentials.loginName,
            passwordEncrypted = encryptedPassword,
            twoFactorEnabled = false, // Already handled via web login
            authTokenEncrypted = encryptedAuthToken,
            isActive = true
        )

        accountRepository.insertAccount(account)

        runOnUiThread {
            // Navigate to main activity
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Login Failed")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ ->
                finish()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Properly clean up WebView to prevent memory leaks
        _binding?.webView?.apply {
            loadUrl("about:blank")
            clearHistory()
            clearCache(true)
            onPause()
            removeAllViews()
            destroy()
        }
        _binding = null
    }
}
