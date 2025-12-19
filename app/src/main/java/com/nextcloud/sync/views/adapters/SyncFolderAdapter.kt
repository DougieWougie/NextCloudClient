package com.nextcloud.sync.views.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nextcloud.sync.databinding.ItemSyncFolderBinding
import com.nextcloud.sync.models.database.entities.FolderEntity

class SyncFolderAdapter(
    private val onFolderClick: (FolderEntity) -> Unit,
    private val onDeleteClick: (FolderEntity) -> Unit
) : ListAdapter<FolderEntity, SyncFolderAdapter.FolderViewHolder>(FolderDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemSyncFolderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FolderViewHolder(binding, onFolderClick, onDeleteClick)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class FolderViewHolder(
        private val binding: ItemSyncFolderBinding,
        private val onFolderClick: (FolderEntity) -> Unit,
        private val onDeleteClick: (FolderEntity) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: FolderEntity) {
            binding.textLocalPath.text = folder.localPath.split(":").lastOrNull() ?: folder.localPath
            binding.textRemotePath.text = folder.remotePath

            val syncType = if (folder.twoWaySync) "Two-way sync" else "Download only"
            val wifiOnly = if (folder.wifiOnly) " • WiFi only" else ""
            binding.textSyncInfo.text = "$syncType$wifiOnly"

            binding.root.setOnClickListener {
                onFolderClick(folder)
            }

            binding.buttonDelete.setOnClickListener {
                onDeleteClick(folder)
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
