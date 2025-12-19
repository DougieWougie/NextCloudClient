package com.nextcloud.sync.views.activities

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nextcloud.sync.controllers.auth.TwoFactorController
import com.nextcloud.sync.databinding.ActivityTwoFactorBinding
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.network.NextcloudAuthenticator
import com.nextcloud.sync.models.repository.AccountRepository
import kotlinx.coroutines.launch

class TwoFactorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTwoFactorBinding
    private lateinit var twoFactorController: TwoFactorController
    private var accountId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTwoFactorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        accountId = intent.getLongExtra("account_id", 0)

        setupController()
        setupViews()
    }

    private fun setupController() {
        val db = AppDatabase.getInstance(this)
        val accountRepository = AccountRepository(db.accountDao())
        val nextcloudAuthenticator = NextcloudAuthenticator()

        twoFactorController = TwoFactorController(accountRepository, nextcloudAuthenticator)
    }

    private fun setupViews() {
        binding.buttonVerify.setOnClickListener {
            performVerification()
        }
    }

    private fun performVerification() {
        val otpCode = binding.editOtpCode.text.toString()

        binding.progressBar.visibility = View.VISIBLE
        binding.buttonVerify.isEnabled = false

        lifecycleScope.launch {
            twoFactorController.verifyTwoFactor(accountId, otpCode, object : TwoFactorController.TwoFactorCallback {
                override fun onTwoFactorSuccess(accountId: Long) {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        // Navigate to main activity
                        startActivity(Intent(this@TwoFactorActivity, MainActivity::class.java))
                        finish()
                    }
                }

                override fun onTwoFactorError(error: String) {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        binding.buttonVerify.isEnabled = true
                        showError(error)
                    }
                }

                override fun onInvalidCode() {
                    runOnUiThread {
                        binding.progressBar.visibility = View.GONE
                        binding.buttonVerify.isEnabled = true
                        binding.textInputLayoutOtp.error = "Invalid code. Please enter a 6-digit code."
                    }
                }
            })
        }
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Verification Failed")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
