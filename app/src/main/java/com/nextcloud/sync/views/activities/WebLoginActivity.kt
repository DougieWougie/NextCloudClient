package com.nextcloud.sync.views.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
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
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun startLoginFlow() {
        binding.progressBar.visibility = View.VISIBLE
        loginFlow = NextcloudLoginFlow(serverUrl)

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

    override fun onBackPressed() {
        if (_binding?.webView?.canGoBack() == true) {
            _binding?.webView?.goBack()
        } else {
            super.onBackPressed()
        }
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
            destroyDrawingCache()
            destroy()
        }
        _binding = null
    }
}
