package com.nextcloud.sync.views.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nextcloud.sync.databinding.ActivityFileBrowserBinding
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.utils.EncryptionUtil
import com.nextcloud.sync.utils.PathValidator
import com.nextcloud.sync.views.adapters.RemoteFolderAdapter
import kotlinx.coroutines.launch

class FileBrowserActivity : AppCompatActivity() {
    private var _binding: ActivityFileBrowserBinding? = null
    private val binding get() = _binding!!
    private lateinit var accountRepository: AccountRepository
    private lateinit var webDavClient: WebDavClient
    private lateinit var adapter: RemoteFolderAdapter

    private var currentPath: String = "/"
    private val folderStack = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentPath = intent.getStringExtra("current_path") ?: "/"

        setupToolbar()
        setupRepository()
        setupRecyclerView()
        setupViews()
        setupBackPressedHandler()
        loadAccount()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Select Folder"
        updatePathDisplay()
    }

    private fun setupRepository() {
        val db = AppDatabase.getInstance(this)
        accountRepository = AccountRepository(db.accountDao())
    }

    private fun setupRecyclerView() {
        adapter = RemoteFolderAdapter { folder ->
            navigateToFolder(folder.name)
        }
        binding.recyclerFolders.layoutManager = LinearLayoutManager(this)
        binding.recyclerFolders.adapter = adapter
    }

    private fun setupViews() {
        binding.buttonSelectFolder.setOnClickListener {
            selectCurrentFolder()
        }

        binding.buttonParentFolder.setOnClickListener {
            navigateUp()
        }

        binding.fabCreateFolder.setOnClickListener {
            showCreateFolderDialog()
        }
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (folderStack.isNotEmpty()) {
                    navigateUp()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun loadAccount() {
        lifecycleScope.launch {
            val account = accountRepository.getActiveAccount()
            if (account == null) {
                Snackbar.make(binding.root, "No active account found", Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.fabCreateFolder).show()
                finish()
                return@launch
            }

            val password = EncryptionUtil.decryptPassword(account.passwordEncrypted)
            val authToken = account.authTokenEncrypted?.let { EncryptionUtil.decryptPassword(it) } ?: password
            webDavClient = WebDavClient(this@FileBrowserActivity, account.serverUrl, account.username, authToken)

            loadFolders()
        }
    }

    private fun loadFolders() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerFolders.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val folders = webDavClient.listFolders(currentPath)

                val folderItems = folders.map { FolderItem(it.name, it.path) }

                adapter.submitList(folderItems)

                binding.progressBar.visibility = View.GONE
                binding.recyclerFolders.visibility = View.VISIBLE

                if (folderItems.isEmpty()) {
                    binding.textEmptyState.visibility = View.VISIBLE
                } else {
                    binding.textEmptyState.visibility = View.GONE
                }

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Snackbar.make(binding.root, "Failed to load folders: ${e.message}", Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.fabCreateFolder).show()
            }
        }
    }

    private fun navigateToFolder(folderName: String) {
        // Sanitize folder name to prevent directory traversal
        val sanitizedName = PathValidator.sanitizeFileName(folderName)
        if (sanitizedName == null) {
            Snackbar.make(binding.root, "Invalid folder name", Snackbar.LENGTH_SHORT)
                .setAnchorView(binding.fabCreateFolder).show()
            return
        }

        folderStack.add(currentPath)
        currentPath = if (currentPath.endsWith("/")) {
            "$currentPath$sanitizedName"
        } else {
            "$currentPath/$sanitizedName"
        }
        updatePathDisplay()
        loadFolders()
    }

    private fun navigateUp() {
        if (folderStack.isNotEmpty()) {
            currentPath = folderStack.removeAt(folderStack.size - 1)
            updatePathDisplay()
            loadFolders()
        }
    }

    private fun updatePathDisplay() {
        binding.textCurrentPath.text = currentPath
        binding.buttonParentFolder.isEnabled = folderStack.isNotEmpty()
    }

    private fun selectCurrentFolder() {
        val intent = Intent()
        intent.putExtra("selected_path", currentPath)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showCreateFolderDialog() {
        val editText = android.widget.EditText(this).apply {
            hint = "Folder name"
            setPadding(50, 40, 50, 40)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Create New Folder")
            .setView(editText)
            .setPositiveButton("Create") { _, _ ->
                val folderName = editText.text.toString().trim()
                if (folderName.isNotEmpty()) {
                    createFolder(folderName)
                } else {
                    Snackbar.make(binding.root, "Folder name cannot be empty", Snackbar.LENGTH_SHORT)
                        .setAnchorView(binding.fabCreateFolder).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createFolder(folderName: String) {
        val newFolderPath = if (currentPath.endsWith("/")) {
            "$currentPath$folderName"
        } else {
            "$currentPath/$folderName"
        }

        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val success = webDavClient.createDirectory(newFolderPath)

                binding.progressBar.visibility = View.GONE

                if (success) {
                    Snackbar.make(binding.root, "Folder created successfully", Snackbar.LENGTH_SHORT)
                        .setAnchorView(binding.fabCreateFolder).show()
                    loadFolders() // Reload to show the new folder
                } else {
                    Snackbar.make(binding.root, "Failed to create folder", Snackbar.LENGTH_LONG)
                        .setAnchorView(binding.fabCreateFolder).show()
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.fabCreateFolder).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    data class FolderItem(
        val name: String,
        val path: String
    )
}
