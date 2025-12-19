package com.nextcloud.sync.views.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nextcloud.sync.R
import com.nextcloud.sync.controllers.auth.LoginController
import com.nextcloud.sync.databinding.ActivityLoginBinding
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.repository.AccountRepository
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var loginController: LoginController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupController()
        setupViews()
    }

    private fun setupController() {
        val db = AppDatabase.getInstance(this)
        val accountRepository = AccountRepository(db.accountDao())

        loginController = LoginController(accountRepository)
    }

    private fun setupViews() {
        binding.buttonLogin.setOnClickListener {
            performWebLogin()
        }
    }

    private fun performWebLogin() {
        val serverUrl = binding.editServerUrl.text.toString().trim()

        // Basic validation
        if (serverUrl.isEmpty()) {
            binding.textInputLayoutServerUrl.error = "Server URL is required"
            return
        }

        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            binding.textInputLayoutServerUrl.error = "URL must start with http:// or https://"
            return
        }

        // Clear errors
        binding.textInputLayoutServerUrl.error = null

        // Navigate to web login activity
        val intent = Intent(this, WebLoginActivity::class.java)
        intent.putExtra("server_url", serverUrl.trimEnd('/'))
        startActivity(intent)
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Login Failed")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
