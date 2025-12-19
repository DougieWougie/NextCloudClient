package com.nextcloud.sync.views.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nextcloud.sync.R
import com.nextcloud.sync.databinding.ActivityMainBinding
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.models.repository.FolderRepository
import com.nextcloud.sync.services.workers.SyncWorker
import com.nextcloud.sync.views.adapters.SyncFolderAdapter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var folderRepository: FolderRepository
    private lateinit var accountRepository: AccountRepository
    private lateinit var folderAdapter: SyncFolderAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRepository()
        setupRecyclerView()
        setupViews()
        loadFolders()
        loadLastSyncTime()
    }

    override fun onResume() {
        super.onResume()
        loadFolders()
        loadLastSyncTime()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
    }

    private fun setupRepository() {
        val db = AppDatabase.getInstance(this)
        folderRepository = FolderRepository(db.folderDao())
        accountRepository = AccountRepository(db.accountDao())
    }

    private fun setupRecyclerView() {
        folderAdapter = SyncFolderAdapter(
            onFolderClick = { folder ->
                // Future: Navigate to folder details
            },
            onDeleteClick = { folder ->
                showDeleteConfirmation(folder)
            }
        )
        binding.recyclerFolders.layoutManager = LinearLayoutManager(this)
        binding.recyclerFolders.adapter = folderAdapter
    }

    private fun setupViews() {
        binding.fabSync.setOnClickListener {
            startSync()
        }

        binding.buttonAddFolder.setOnClickListener {
            val intent = Intent(this, AddFolderActivity::class.java)
            startActivityForResult(intent, REQUEST_ADD_FOLDER)
        }
    }

    private fun loadFolders() {
        lifecycleScope.launch {
            val account = accountRepository.getActiveAccount()
            if (account != null) {
                val folders = folderRepository.getFoldersByAccount(account.id)
                folderAdapter.submitList(folders)

                if (folders.isEmpty()) {
                    binding.recyclerFolders.visibility = View.GONE
                    binding.textEmptyState.visibility = View.VISIBLE
                } else {
                    binding.recyclerFolders.visibility = View.VISIBLE
                    binding.textEmptyState.visibility = View.GONE
                }
            }
        }
    }

    private fun loadLastSyncTime() {
        lifecycleScope.launch {
            val account = accountRepository.getActiveAccount()
            if (account?.lastSync != null) {
                val dateFormat = SimpleDateFormat("MMM dd, yyyy 'at' HH:mm", Locale.getDefault())
                val dateString = dateFormat.format(Date(account.lastSync))
                binding.textLastSync.text = "Last synced: $dateString"
            } else {
                binding.textLastSync.text = getString(R.string.never_synced)
            }
        }
    }

    private fun startSync() {
        binding.progressSync.visibility = View.VISIBLE
        SyncWorker.scheduleImmediate(this)
        Snackbar.make(binding.root, "Sync started", Snackbar.LENGTH_SHORT).show()

        // Hide progress after a delay (in a real app, this would be based on actual sync completion)
        binding.progressSync.postDelayed({
            binding.progressSync.visibility = View.GONE
            loadLastSyncTime()
        }, 3000)
    }

    private fun showDeleteConfirmation(folder: com.nextcloud.sync.models.database.entities.FolderEntity) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Sync Folder")
            .setMessage("Are you sure you want to remove this folder from sync? Local files will not be deleted.")
            .setPositiveButton("Delete") { _, _ ->
                deleteFolder(folder)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteFolder(folder: com.nextcloud.sync.models.database.entities.FolderEntity) {
        lifecycleScope.launch {
            folderRepository.delete(folder)
            loadFolders()
            Snackbar.make(binding.root, "Folder removed from sync", Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ADD_FOLDER && resultCode == Activity.RESULT_OK) {
            loadFolders()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val REQUEST_ADD_FOLDER = 100
    }
}
