package com.nextcloud.sync.views.activities

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.nextcloud.sync.controllers.sync.ConflictResolutionController
import com.nextcloud.sync.databinding.ActivityConflictResolutionBinding
import com.nextcloud.sync.models.data.ConflictResolution
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.database.entities.ConflictEntity
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.models.repository.ConflictRepository
import com.nextcloud.sync.models.repository.FileRepository
import com.nextcloud.sync.utils.EncryptionUtil
import com.nextcloud.sync.views.adapters.ConflictListAdapter
import kotlinx.coroutines.launch

class ConflictResolutionActivity : AppCompatActivity() {
    private var _binding: ActivityConflictResolutionBinding? = null
    private val binding get() = _binding!!
    private lateinit var conflictController: ConflictResolutionController
    private lateinit var adapter: ConflictListAdapter
    private val conflicts = mutableListOf<ConflictEntity>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityConflictResolutionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupController()
        setupViews()
        loadConflicts()
    }

    private fun setupController() {
        val db = AppDatabase.getInstance(this)
        val accountRepository = AccountRepository(db.accountDao())

        lifecycleScope.launch {
            val account = accountRepository.getActiveAccount()
            if (account != null) {
                val password = EncryptionUtil.decryptPassword(account.passwordEncrypted)
                val authToken = account.authTokenEncrypted?.let { EncryptionUtil.decryptPassword(it) } ?: password
                val webDavClient = WebDavClient(account.serverUrl, account.username, authToken)

                conflictController = ConflictResolutionController(
                    ConflictRepository(db.conflictDao()),
                    FileRepository(db.fileDao()),
                    webDavClient
                )
            }
        }
    }

    private fun setupViews() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Resolve Conflicts"

        adapter = ConflictListAdapter(conflicts) { conflict, resolution ->
            resolveConflict(conflict, resolution)
        }

        binding.recyclerViewConflicts.apply {
            layoutManager = LinearLayoutManager(this@ConflictResolutionActivity)
            adapter = this@ConflictResolutionActivity.adapter
        }
    }

    private fun loadConflicts() {
        lifecycleScope.launch {
            val pendingConflicts = if (::conflictController.isInitialized) {
                conflictController.getPendingConflicts()
            } else {
                emptyList()
            }

            runOnUiThread {
                if (pendingConflicts.isEmpty()) {
                    binding.textEmptyState.visibility = View.VISIBLE
                    binding.recyclerViewConflicts.visibility = View.GONE
                } else {
                    binding.textEmptyState.visibility = View.GONE
                    binding.recyclerViewConflicts.visibility = View.VISIBLE
                    conflicts.clear()
                    conflicts.addAll(pendingConflicts)
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun resolveConflict(conflict: ConflictEntity, resolution: ConflictResolution) {
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            conflictController.resolveConflict(
                conflict.id,
                resolution,
                object : ConflictResolutionController.ConflictResolutionCallback {
                    override fun onResolutionSuccess() {
                        runOnUiThread {
                            binding.progressBar.visibility = View.GONE
                            Snackbar.make(binding.root, "Conflict resolved", Snackbar.LENGTH_SHORT).show()
                            loadConflicts() // Reload list
                        }
                    }

                    override fun onResolutionError(error: String) {
                        runOnUiThread {
                            binding.progressBar.visibility = View.GONE
                            Snackbar.make(binding.root, "Error: $error", Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            )
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
