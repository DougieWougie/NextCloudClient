package com.nextcloud.sync.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Helper to convert URIs and file paths to human-readable folder names
 */
object UriPathHelper {

    /**
     * Get a human-readable display name from a path (URI or file path)
     */
    fun getDisplayName(context: Context, path: String): String {
        return if (path.startsWith("content://")) {
            getDisplayNameFromUri(context, path)
        } else {
            getDisplayNameFromFilePath(path)
        }
    }

    /**
     * Get display name from a content URI
     * Examples:
     * - content://com.android.externalstorage.documents/tree/primary:DCIM -> DCIM
     * - content://com.android.externalstorage.documents/tree/primary:Documents/MyFolder -> Documents/MyFolder
     */
    private fun getDisplayNameFromUri(context: Context, uriString: String): String {
        return try {
            val uri = Uri.parse(uriString)

            // Try to get the document name from DocumentFile
            val documentFile = DocumentFile.fromTreeUri(context, uri)
            if (documentFile != null && documentFile.name != null) {
                return documentFile.name!!
            }

            // Fallback: parse the tree document ID
            val treeDocumentId = DocumentsContract.getTreeDocumentId(uri)

            // Extract the readable part after the colon
            // Format is usually "primary:DCIM" or "primary:Documents/MyFolder"
            if (treeDocumentId.contains(":")) {
                val parts = treeDocumentId.split(":")
                if (parts.size >= 2) {
                    val pathPart = parts[1]
                    // Decode URI encoding
                    return Uri.decode(pathPart)
                }
            }

            // If we can't parse it, return the document ID
            treeDocumentId
        } catch (e: Exception) {
            // Last resort: return a generic name
            "Selected Folder"
        }
    }

    /**
     * Get display name from a file path
     * Example: /storage/emulated/0/DCIM -> DCIM
     */
    private fun getDisplayNameFromFilePath(path: String): String {
        return path.substringAfterLast('/')
    }

    /**
     * Get a shortened display path showing parent structure if available
     * Example: Documents/Photos instead of just Photos
     */
    fun getShortDisplayPath(context: Context, path: String, maxSegments: Int = 2): String {
        return if (path.startsWith("content://")) {
            getShortDisplayPathFromUri(context, path, maxSegments)
        } else {
            getShortDisplayPathFromFilePath(path, maxSegments)
        }
    }

    private fun getShortDisplayPathFromUri(context: Context, uriString: String, maxSegments: Int): String {
        return try {
            val uri = Uri.parse(uriString)
            val treeDocumentId = DocumentsContract.getTreeDocumentId(uri)

            if (treeDocumentId.contains(":")) {
                val parts = treeDocumentId.split(":")
                if (parts.size >= 2) {
                    val pathPart = Uri.decode(parts[1])

                    // Split by / and take last N segments
                    val segments = pathPart.split("/")
                    return if (segments.size > maxSegments) {
                        segments.takeLast(maxSegments).joinToString("/")
                    } else {
                        pathPart
                    }
                }
            }

            getDisplayNameFromUri(context, uriString)
        } catch (e: Exception) {
            getDisplayNameFromUri(context, uriString)
        }
    }

    private fun getShortDisplayPathFromFilePath(path: String, maxSegments: Int): String {
        val segments = path.split("/").filter { it.isNotEmpty() }
        return if (segments.size > maxSegments) {
            segments.takeLast(maxSegments).joinToString("/")
        } else {
            segments.lastOrNull() ?: path
        }
    }

    /**
     * Get storage location description (Internal Storage, SD Card, etc.)
     */
    fun getStorageLocation(path: String): String {
        return when {
            path.startsWith("content://com.android.externalstorage.documents/tree/primary:") ->
                "Internal Storage"
            path.startsWith("content://com.android.externalstorage.documents/tree/") ->
                "SD Card"
            path.contains("/storage/emulated/0") ->
                "Internal Storage"
            path.contains("/storage/") && !path.contains("/emulated/") ->
                "SD Card"
            else ->
                "Device Storage"
        }
    }
}
