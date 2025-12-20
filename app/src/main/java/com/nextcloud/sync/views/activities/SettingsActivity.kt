package com.nextcloud.sync.views.activities

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nextcloud.sync.databinding.ActivitySettingsBinding
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.database.entities.AccountEntity
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.utils.ThemePreference
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private var _binding: ActivitySettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var accountRepository: AccountRepository
    private var currentAccount: AccountEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRepository()
        loadAccountDetails()
        loadThemePreference()
        setupViews()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"
    }

    private fun setupRepository() {
        val db = AppDatabase.getInstance(this)
        accountRepository = AccountRepository(db.accountDao())
    }

    private fun loadAccountDetails() {
        lifecycleScope.launch {
            currentAccount = accountRepository.getActiveAccount()
            currentAccount?.let { account ->
                binding.editServerUrl.setText(account.serverUrl)
                binding.editUsername.setText(account.username)
            }
        }
    }

    private fun loadThemePreference() {
        val currentTheme = ThemePreference.getThemeMode(this)
        binding.textCurrentTheme.text = ThemePreference.getThemeDisplayName(currentTheme)
    }

    private fun setupViews() {
        binding.buttonSave.setOnClickListener {
            saveAccountDetails()
        }

        binding.buttonChangeTheme.setOnClickListener {
            showThemeDialog()
        }

        binding.buttonLogout.setOnClickListener {
            showLogoutConfirmation()
        }
    }

    private fun saveAccountDetails() {
        val serverUrl = binding.editServerUrl.text.toString().trim()
        val username = binding.editUsername.text.toString().trim()

        if (serverUrl.isEmpty()) {
            binding.textInputLayoutServerUrl.error = "Server URL is required"
            return
        }

        if (username.isEmpty()) {
            binding.textInputLayoutUsername.error = "Username is required"
            return
        }

        // Add https:// prefix if no protocol is specified
        val normalizedUrl = if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            "https://$serverUrl"
        } else {
            serverUrl
        }

        binding.textInputLayoutServerUrl.error = null
        binding.textInputLayoutUsername.error = null

        lifecycleScope.launch {
            currentAccount?.let { account ->
                val updatedAccount = account.copy(
                    serverUrl = normalizedUrl.trimEnd('/'),
                    username = username
                )
                accountRepository.updateAccount(updatedAccount)
                Snackbar.make(binding.root, "Account updated successfully", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showThemeDialog() {
        val currentTheme = ThemePreference.getThemeMode(this)
        val themeOptions = arrayOf(
            ThemePreference.getThemeDisplayName(ThemePreference.THEME_AUTO),
            ThemePreference.getThemeDisplayName(ThemePreference.THEME_LIGHT),
            ThemePreference.getThemeDisplayName(ThemePreference.THEME_DARK)
        )
        val themeValues = arrayOf(
            ThemePreference.THEME_AUTO,
            ThemePreference.THEME_LIGHT,
            ThemePreference.THEME_DARK
        )

        val currentIndex = when (currentTheme) {
            ThemePreference.THEME_LIGHT -> 1
            ThemePreference.THEME_DARK -> 2
            else -> 0
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Choose Theme")
            .setSingleChoiceItems(themeOptions, currentIndex) { dialog, which ->
                val selectedTheme = themeValues[which]
                ThemePreference.setThemeMode(this, selectedTheme)
                loadThemePreference()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLogoutConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout? This will remove your account from this device.")
            .setPositiveButton("Logout") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {
        lifecycleScope.launch {
            currentAccount?.let { account ->
                accountRepository.deleteAccount(account)
            }

            // Navigate back to login activity
            val intent = Intent(this@SettingsActivity, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }
}
