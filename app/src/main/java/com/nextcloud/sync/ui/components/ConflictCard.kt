package com.nextcloud.sync.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextcloud.sync.models.data.ConflictResolution
import com.nextcloud.sync.models.database.entities.ConflictEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConflictCard(
    conflict: ConflictEntity,
    onResolve: (ConflictResolution) -> Unit,
    modifier: Modifier = Modifier
) {
    val fileName = conflict.localVersionPath.substringAfterLast('/')
    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // File name
            Text(
                text = fileName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Local version info
            Text(
                text = "Local: ${formatSize(conflict.localSize)}, ${dateFormat.format(Date(conflict.localModified))}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Remote version info
            Text(
                text = "Remote: ${formatSize(conflict.remoteSize)}, ${dateFormat.format(Date(conflict.remoteModified))}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onResolve(ConflictResolution.KEEP_LOCAL) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Keep Local")
                }

                OutlinedButton(
                    onClick = { onResolve(ConflictResolution.KEEP_REMOTE) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Keep Remote")
                }

                OutlinedButton(
                    onClick = { onResolve(ConflictResolution.KEEP_BOTH) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Keep Both")
                }
            }
        }
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
