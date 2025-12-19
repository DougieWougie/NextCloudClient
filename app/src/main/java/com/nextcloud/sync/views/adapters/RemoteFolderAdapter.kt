package com.nextcloud.sync.views.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nextcloud.sync.databinding.ItemRemoteFolderBinding
import com.nextcloud.sync.views.activities.FileBrowserActivity

class RemoteFolderAdapter(
    private val onFolderClick: (FileBrowserActivity.FolderItem) -> Unit
) : ListAdapter<FileBrowserActivity.FolderItem, RemoteFolderAdapter.FolderViewHolder>(FolderDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemRemoteFolderBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return FolderViewHolder(binding, onFolderClick)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class FolderViewHolder(
        private val binding: ItemRemoteFolderBinding,
        private val onFolderClick: (FileBrowserActivity.FolderItem) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(folder: FileBrowserActivity.FolderItem) {
            binding.textFolderName.text = folder.name
            binding.root.setOnClickListener {
                onFolderClick(folder)
            }
        }
    }

    private class FolderDiffCallback : DiffUtil.ItemCallback<FileBrowserActivity.FolderItem>() {
        override fun areItemsTheSame(
            oldItem: FileBrowserActivity.FolderItem,
            newItem: FileBrowserActivity.FolderItem
        ): Boolean = oldItem.path == newItem.path

        override fun areContentsTheSame(
            oldItem: FileBrowserActivity.FolderItem,
            newItem: FileBrowserActivity.FolderItem
        ): Boolean = oldItem == newItem
    }
}
