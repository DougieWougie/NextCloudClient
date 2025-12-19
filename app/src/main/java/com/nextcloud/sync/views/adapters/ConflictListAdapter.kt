package com.nextcloud.sync.views.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nextcloud.sync.databinding.ItemConflictBinding
import com.nextcloud.sync.models.data.ConflictResolution
import com.nextcloud.sync.models.database.entities.ConflictEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConflictListAdapter(
    private val conflicts: List<ConflictEntity>,
    private val onResolve: (ConflictEntity, ConflictResolution) -> Unit
) : RecyclerView.Adapter<ConflictListAdapter.ConflictViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConflictViewHolder {
        val binding = ItemConflictBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ConflictViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ConflictViewHolder, position: Int) {
        holder.bind(conflicts[position])
    }

    override fun getItemCount(): Int = conflicts.size

    inner class ConflictViewHolder(
        private val binding: ItemConflictBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(conflict: ConflictEntity) {
            // Extract filename from path
            val fileName = conflict.localVersionPath.substringAfterLast('/')

            binding.textFileName.text = fileName

            val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

            binding.textLocalVersion.text = "Local: ${formatSize(conflict.localSize)}, " +
                    dateFormat.format(Date(conflict.localModified))

            binding.textRemoteVersion.text = "Remote: ${formatSize(conflict.remoteSize)}, " +
                    dateFormat.format(Date(conflict.remoteModified))

            binding.buttonKeepLocal.setOnClickListener {
                onResolve(conflict, ConflictResolution.KEEP_LOCAL)
            }

            binding.buttonKeepRemote.setOnClickListener {
                onResolve(conflict, ConflictResolution.KEEP_REMOTE)
            }

            binding.buttonKeepBoth.setOnClickListener {
                onResolve(conflict, ConflictResolution.KEEP_BOTH)
            }
        }

        private fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                else -> "${bytes / (1024 * 1024 * 1024)} GB"
            }
        }
    }
}
