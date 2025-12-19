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
            performLogin()
        }
    }

    private fun performLogin() {
        val serverUrl = binding.editServerUrl.text.toString()
        val username = binding.editUsername.text.toString()
        val password = binding.editPassword.text.toString()

        binding.progressBar.visibility = View.VISIBLE
        binding.buttonLogin.isEnabled = false

        lifecycleScope.launch {
            loginController.login(serverUrl, username, password, object : LoginController.LoginCallback {
                override fun onLoginSuccess(requiresTwoFactor: Boolean, accountId: Long) {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE

                        if (requiresTwoFactor) {
                            // Navigate to 2FA activity
                            val intent = Intent(this@LoginActivity, TwoFactorActivity::class.java)
                            intent.putExtra("account_id", accountId)
                            startActivity(intent)
                            finish()
                        } else {
                            // Navigate to main activity
                            startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                            finish()
                        }
                    }
                }

                override fun onLoginError(error: String) {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        binding.buttonLogin.isEnabled = true
                        showError(error)
                    }
                }

                override fun onValidationError(field: String, error: String) {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        binding.buttonLogin.isEnabled = true

                        when (field) {
                            "server_url" -> binding.textInputLayoutServerUrl.error = error
                            "username" -> binding.textInputLayoutUsername.error = error
                            "password" -> binding.textInputLayoutPassword.error = error
                        }
                    }
                }
            })
        }
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Login Failed")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
