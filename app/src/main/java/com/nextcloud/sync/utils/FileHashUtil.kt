package com.nextcloud.sync.utils

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object FileHashUtil {
    fun calculateHash(file: File): String {
        return file.inputStream().use { calculateHash(it) }
    }

    fun calculateHash(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun calculateHashForString(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
