package com.nextcloud.sync.views.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.nextcloud.sync.R
import com.nextcloud.sync.databinding.ActivityMainBinding
import com.nextcloud.sync.services.workers.SyncWorker

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupFab()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)
    }

    private fun setupFab() {
        binding.fabSync.setOnClickListener {
            // Trigger immediate sync
            SyncWorker.scheduleImmediate(this)
            Snackbar.make(binding.root, "Sync started", Snackbar.LENGTH_SHORT).show()
        }
    }
}
