package com.nextcloud.sync.views.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.nextcloud.sync.databinding.ActivityAddFolderBinding
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.database.entities.FolderEntity
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.models.repository.FolderRepository
import kotlinx.coroutines.launch

class AddFolderActivity : AppCompatActivity() {
    private var _binding: ActivityAddFolderBinding? = null
    private val binding get() = _binding!!
    private lateinit var folderRepository: FolderRepository
    private lateinit var accountRepository: AccountRepository

    private var selectedLocalPath: String? = null
    private var selectedRemotePath: String = "/"
    private var accountId: Long = 0

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            handleFolderSelection(it)
        }
    }

    private val remoteFolderPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedRemotePath = result.data?.getStringExtra("selected_path") ?: "/"
            binding.textRemoteFolder.text = selectedRemotePath
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityAddFolderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRepository()
        loadAccount()
        setupViews()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Add Sync Folder"
    }

    private fun setupRepository() {
        val db = AppDatabase.getInstance(this)
        folderRepository = FolderRepository(db.folderDao())
        accountRepository = AccountRepository(db.accountDao())
    }

    private fun loadAccount() {
        lifecycleScope.launch {
            val account = accountRepository.getActiveAccount()
            if (account == null) {
                Snackbar.make(binding.root, "No active account found", Snackbar.LENGTH_LONG).show()
                finish()
                return@launch
            }
            accountId = account.id
            binding.textServerUrl.text = account.serverUrl
        }
    }

    private fun setupViews() {
        binding.buttonSelectLocalFolder.setOnClickListener {
            openFolderPicker()
        }

        binding.buttonSelectRemoteFolder.setOnClickListener {
            openFileBrowser()
        }

        binding.buttonAddFolder.setOnClickListener {
            addFolder()
        }

        binding.switchTwoWaySync.isChecked = true
        binding.switchWifiOnly.isChecked = false
    }

    private fun openFolderPicker() {
        folderPicker.launch(null)
    }

    private fun handleFolderSelection(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            val uriString = uri.toString()
            selectedLocalPath = uriString

            // Get readable folder name using UriPathHelper
            val folderName = com.nextcloud.sync.utils.UriPathHelper.getDisplayName(this, uriString)
            val storageLocation = com.nextcloud.sync.utils.UriPathHelper.getStorageLocation(uriString)

            binding.textLocalFolder.text = folderName
            binding.textLocalFolderPath.text = storageLocation
            binding.textLocalFolderPath.visibility = android.view.View.VISIBLE
        } catch (e: Exception) {
            Snackbar.make(binding.root, "Failed to select folder: ${e.message}", Snackbar.LENGTH_LONG).show()
        }
    }

    private fun openFileBrowser() {
        val intent = Intent(this, FileBrowserActivity::class.java)
        intent.putExtra("current_path", selectedRemotePath)
        remoteFolderPicker.launch(intent)
    }

    private fun addFolder() {
        val localPath = selectedLocalPath
        if (localPath == null) {
            Snackbar.make(binding.root, "Please select a local folder", Snackbar.LENGTH_SHORT).show()
            return
        }

        if (selectedRemotePath.isEmpty()) {
            Snackbar.make(binding.root, "Please select a remote folder", Snackbar.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val folder = FolderEntity(
                accountId = accountId,
                localPath = localPath,
                remotePath = selectedRemotePath,
                syncEnabled = true,
                twoWaySync = binding.switchTwoWaySync.isChecked,
                wifiOnly = binding.switchWifiOnly.isChecked
            )

            folderRepository.insert(folder)

            Snackbar.make(binding.root, "Folder added successfully", Snackbar.LENGTH_SHORT).show()

            setResult(Activity.RESULT_OK)
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
