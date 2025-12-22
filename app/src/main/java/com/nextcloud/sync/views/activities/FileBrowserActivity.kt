package com.nextcloud.sync.views.activities

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nextcloud.sync.R
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

    // Search/filter support
    private var allFolders: List<FolderItem> = emptyList()
    private var filteredFolders: List<FolderItem> = emptyList()
    private var currentSearchQuery: String = ""

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

        // Handle navigation icon click
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        setupBreadcrumbs()
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

        binding.fabCreateFolder.setOnClickListener {
            showCreateFolderDialog()
        }

        // Setup search functionality
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String?): Boolean {
                currentSearchQuery = newText ?: ""
                filterFolders()
                return true
            }

            override fun onQueryTextSubmit(query: String?): Boolean {
                // Optional: hide keyboard or perform other actions
                return true
            }
        })
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
                    .setAnchorView(binding.bottomAppBar).show()
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
        binding.layoutEmptyState.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val folders = webDavClient.listFolders(currentPath)
                allFolders = folders.map { FolderItem(it.name, it.path) }

                // Apply current filter
                filterFolders()

                binding.progressBar.visibility = View.GONE
                binding.recyclerFolders.visibility = View.VISIBLE

                updateEmptyState()

            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Snackbar.make(binding.root, "Failed to load folders: ${e.message}", Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.bottomAppBar).show()
            }
        }
    }

    private fun navigateToFolder(folderName: String) {
        // Sanitize folder name to prevent directory traversal
        val sanitizedName = PathValidator.sanitizeFileName(folderName)
        if (sanitizedName == null) {
            Snackbar.make(binding.root, "Invalid folder name", Snackbar.LENGTH_SHORT)
                .setAnchorView(binding.bottomAppBar).show()
            return
        }

        folderStack.add(currentPath)
        currentPath = if (currentPath.endsWith("/")) {
            "$currentPath$sanitizedName"
        } else {
            "$currentPath/$sanitizedName"
        }

        // Clear search when navigating
        currentSearchQuery = ""
        binding.searchView.setQuery("", false)

        setupBreadcrumbs()
        loadFolders()
    }

    private fun navigateUp() {
        if (folderStack.isNotEmpty()) {
            currentPath = folderStack.removeAt(folderStack.size - 1)

            // Clear search when navigating
            currentSearchQuery = ""
            binding.searchView.setQuery("", false)

            setupBreadcrumbs()
            loadFolders()
        }
    }

    private fun selectCurrentFolder() {
        val intent = Intent()
        intent.putExtra("selected_path", currentPath)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    private fun showCreateFolderDialog() {
        val textInputLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            setPadding(50, 20, 50, 0)
        }

        val editText = com.google.android.material.textfield.TextInputEditText(textInputLayout.context).apply {
            hint = "Folder name"
        }

        textInputLayout.addView(editText)

        MaterialAlertDialogBuilder(this)
            .setTitle("Create New Folder")
            .setView(textInputLayout)
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
                        .setAnchorView(binding.bottomAppBar).show()
                    loadFolders() // Reload to show the new folder
                } else {
                    Snackbar.make(binding.root, "Failed to create folder", Snackbar.LENGTH_LONG)
                        .setAnchorView(binding.bottomAppBar).show()
                }
            } catch (e: Exception) {
                binding.progressBar.visibility = View.GONE
                Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.bottomAppBar).show()
            }
        }
    }

    // Search/filter functionality
    private fun filterFolders() {
        filteredFolders = if (currentSearchQuery.isEmpty()) {
            allFolders
        } else {
            allFolders.filter { folder ->
                folder.name.contains(currentSearchQuery, ignoreCase = true)
            }
        }

        adapter.submitList(filteredFolders)
        updateEmptyState()
    }

    private fun updateEmptyState() {
        if (filteredFolders.isEmpty()) {
            binding.recyclerFolders.visibility = View.GONE
            binding.layoutEmptyState.visibility = View.VISIBLE

            // Different messages for search vs no folders
            if (currentSearchQuery.isNotEmpty()) {
                binding.textEmptyState.text = "No folders match \"$currentSearchQuery\""
                binding.iconEmptyState.setImageResource(R.drawable.ic_search_off_24)
            } else {
                binding.textEmptyState.text = "No folders found"
                binding.iconEmptyState.setImageResource(R.drawable.ic_folder_off_24)
            }
        } else {
            binding.recyclerFolders.visibility = View.VISIBLE
            binding.layoutEmptyState.visibility = View.GONE
        }
    }

    // Breadcrumb navigation
    private fun setupBreadcrumbs() {
        binding.chipGroupBreadcrumbs.removeAllViews()

        // Parse current path into segments
        val segments = currentPath.split("/").filter { it.isNotEmpty() }

        // Always add root chip
        val rootChip = createBreadcrumbChip("Home", "/", isLast = segments.isEmpty())
        binding.chipGroupBreadcrumbs.addView(rootChip)

        // Add chips for each path segment
        var pathBuilder = ""
        segments.forEachIndexed { index, segment ->
            pathBuilder += "/$segment"
            val isLast = (index == segments.size - 1)
            val chip = createBreadcrumbChip(segment, pathBuilder, isLast)
            binding.chipGroupBreadcrumbs.addView(chip)
        }

        // Auto-scroll to end to show current path
        binding.breadcrumbScrollView.post {
            binding.breadcrumbScrollView.fullScroll(View.FOCUS_RIGHT)
        }
    }

    private fun createBreadcrumbChip(label: String, path: String, isLast: Boolean): Chip {
        return Chip(this).apply {
            text = label
            isClickable = !isLast
            isEnabled = !isLast

            // Style based on position
            if (isLast) {
                chipBackgroundColor = ColorStateList.valueOf(
                    getColorFromAttr(com.google.android.material.R.attr.colorPrimaryContainer)
                )
                setTextColor(getColorFromAttr(com.google.android.material.R.attr.colorOnPrimaryContainer))
            } else {
                chipBackgroundColor = ColorStateList.valueOf(
                    getColorFromAttr(com.google.android.material.R.attr.colorSurfaceVariant)
                )
                setTextColor(getColorFromAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            }

            // Click handler for parent chips
            if (!isLast) {
                setOnClickListener {
                    navigateToPath(path)
                }

                // Add chevron icon
                chipIcon = ContextCompat.getDrawable(context, R.drawable.ic_chevron_right_24)
                chipIconTint = ColorStateList.valueOf(
                    getColorFromAttr(com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
                iconEndPadding = 4.dpToPx()
            }
        }
    }

    private fun navigateToPath(targetPath: String) {
        // Calculate how many levels to go back
        val currentSegments = currentPath.split("/").filter { it.isNotEmpty() }
        val targetSegments = targetPath.split("/").filter { it.isNotEmpty() }

        val levelsBack = currentSegments.size - targetSegments.size

        // Update folder stack
        repeat(levelsBack) {
            if (folderStack.isNotEmpty()) {
                folderStack.removeAt(folderStack.size - 1)
            }
        }

        currentPath = targetPath

        // Clear search when navigating
        currentSearchQuery = ""
        binding.searchView.setQuery("", false)

        setupBreadcrumbs()
        loadFolders()
    }

    // Helper functions
    private fun getColorFromAttr(@AttrRes attrColor: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attrColor, typedValue, true)
        return typedValue.data
    }

    private fun Int.dpToPx(): Float = this * resources.displayMetrics.density

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    data class FolderItem(
        val name: String,
        val path: String
    )
}
