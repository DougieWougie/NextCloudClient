package com.nextcloud.sync.views.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.nextcloud.sync.databinding.ActivityEditFolderBinding
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.repository.FolderRepository
import com.nextcloud.sync.utils.UriPathHelper
import kotlinx.coroutines.launch

class EditFolderActivity : AppCompatActivity() {
    private var _binding: ActivityEditFolderBinding? = null
    private val binding get() = _binding!!
    private lateinit var folderRepository: FolderRepository

    private var folderId: Long = 0
    private var selectedRemotePath: String = "/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityEditFolderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        folderId = intent.getLongExtra("folder_id", 0)

        if (folderId == 0L) {
            Snackbar.make(binding.root, "Invalid folder ID", Snackbar.LENGTH_LONG).show()
            finish()
            return
        }

        setupToolbar()
        setupRepository()
        loadFolder()
        setupViews()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Edit Sync Folder"
    }

    private fun setupRepository() {
        val db = AppDatabase.getInstance(this)
        folderRepository = FolderRepository(db.folderDao())
    }

    private fun loadFolder() {
        lifecycleScope.launch {
            val folder = folderRepository.getFolderById(folderId)
            if (folder == null) {
                Snackbar.make(binding.root, "Folder not found", Snackbar.LENGTH_LONG).show()
                finish()
                return@launch
            }

            // Display local folder info (read-only)
            val localFolderName = UriPathHelper.getDisplayName(this@EditFolderActivity, folder.localPath)
            val storageLocation = UriPathHelper.getStorageLocation(folder.localPath)

            binding.textLocalFolder.text = localFolderName
            binding.textLocalFolderPath.text = storageLocation
            binding.textLocalFolderPath.visibility = android.view.View.VISIBLE

            // Display remote folder (editable)
            selectedRemotePath = folder.remotePath
            binding.textRemoteFolder.text = selectedRemotePath

            // Set sync options (editable)
            binding.switchTwoWaySync.isChecked = folder.twoWaySync
            binding.switchWifiOnly.isChecked = folder.wifiOnly
        }
    }

    private fun setupViews() {
        binding.buttonSave.setOnClickListener {
            saveChanges()
        }

        binding.buttonSelectRemoteFolder.setOnClickListener {
            openFileBrowser()
        }

        // Make local folder selection non-clickable in edit mode
        binding.cardLocalFolder.isEnabled = false
        binding.cardLocalFolder.alpha = 0.6f

        // Keep remote folder selection enabled
        binding.cardRemoteFolder.isEnabled = true
        binding.cardRemoteFolder.alpha = 1.0f
    }

    private fun openFileBrowser() {
        val intent = Intent(this, FileBrowserActivity::class.java)
        intent.putExtra("current_path", selectedRemotePath)
        startActivityForResult(intent, REQUEST_REMOTE_FOLDER)
    }

    private fun saveChanges() {
        lifecycleScope.launch {
            val folder = folderRepository.getFolderById(folderId) ?: return@launch

            val updatedFolder = folder.copy(
                remotePath = selectedRemotePath,
                twoWaySync = binding.switchTwoWaySync.isChecked,
                wifiOnly = binding.switchWifiOnly.isChecked
            )

            folderRepository.update(updatedFolder)

            Snackbar.make(binding.root, "Sync settings updated", Snackbar.LENGTH_SHORT).show()

            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_REMOTE_FOLDER && resultCode == Activity.RESULT_OK) {
            selectedRemotePath = data?.getStringExtra("selected_path") ?: "/"
            binding.textRemoteFolder.text = selectedRemotePath
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

    companion object {
        private const val REQUEST_REMOTE_FOLDER = 100
    }
}
