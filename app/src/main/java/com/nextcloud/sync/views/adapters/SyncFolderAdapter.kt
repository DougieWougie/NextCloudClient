package com.nextcloud.sync.views.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nextcloud.sync.R
import com.nextcloud.sync.databinding.ItemSyncFolderBinding
import com.nextcloud.sync.models.database.entities.FolderEntity
import com.nextcloud.sync.utils.UriPathHelper

class SyncFolderAdapter(
    private val onFolderClick: (FolderEntity) -> Unit,
    private val onSyncClick: (FolderEntity) -> Unit,
    private val onEditClick: (FolderEntity) -> Unit,
    private val onDeleteClick: (FolderEntity) -> Unit
) : ListAdapter<FolderEntity, SyncFolderAdapter.FolderViewHolder>(FolderDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemSyncFolderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FolderViewHolder(binding, onFolderClick, onSyncClick, onEditClick, onDeleteClick)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class FolderViewHolder(
        private val binding: ItemSyncFolderBinding,
        private val onFolderClick: (FolderEntity) -> Unit,
        private val onSyncClick: (FolderEntity) -> Unit,
        private val onEditClick: (FolderEntity) -> Unit,
        private val onDeleteClick: (FolderEntity) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: FolderEntity) {
            val context = binding.root.context

            // Get readable local folder name
            val localFolderName = UriPathHelper.getDisplayName(context, folder.localPath)
            val storageLocation = UriPathHelper.getStorageLocation(folder.localPath)

            binding.textLocalPath.text = localFolderName
            binding.textRemotePath.text = "${folder.remotePath} ($storageLocation)"

            val syncType = if (folder.twoWaySync) "Two-way sync" else "Download only"
            val wifiOnly = if (folder.wifiOnly) " • WiFi only" else ""
            binding.textSyncInfo.text = "$syncType$wifiOnly"

            binding.root.setOnClickListener {
                onFolderClick(folder)
            }

            binding.buttonSync.setOnClickListener {
                onSyncClick(folder)
            }

            binding.buttonMenu.setOnClickListener { view ->
                val popupMenu = PopupMenu(context, view)
                popupMenu.inflate(R.menu.menu_folder_actions)

                // Force show icons in popup menu
                try {
                    val fieldMPopup = PopupMenu::class.java.getDeclaredField("mPopup")
                    fieldMPopup.isAccessible = true
                    val mPopup = fieldMPopup.get(popupMenu)
                    mPopup.javaClass
                        .getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                        .invoke(mPopup, true)
                } catch (e: Exception) {
                    // Ignore if reflection fails
                }

                popupMenu.setOnMenuItemClickListener { menuItem ->
                    when (menuItem.itemId) {
                        R.id.action_edit -> {
                            onEditClick(folder)
                            true
                        }
                        R.id.action_remove -> {
                            onDeleteClick(folder)
                            true
                        }
                        else -> false
                    }
                }
                popupMenu.show()
            }
        }
    }

    private class FolderDiffCallback : DiffUtil.ItemCallback<FolderEntity>() {
        override fun areItemsTheSame(
            oldItem: FolderEntity,
            newItem: FolderEntity
        ): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(
            oldItem: FolderEntity,
            newItem: FolderEntity
        ): Boolean = oldItem == newItem
    }
}
