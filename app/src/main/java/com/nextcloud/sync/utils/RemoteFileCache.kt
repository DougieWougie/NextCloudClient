package com.nextcloud.sync.utils

import com.nextcloud.sync.controllers.fileops.RemoteFileManagerController
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory cache for remote file listings with ETag-based invalidation.
 *
 * Caches directory listings to avoid repeated WebDAV PROPFIND requests.
 * Uses ETags to detect changes on the server.
 *
 * Thread-safe implementation using ConcurrentHashMap and Mutex for write operations.
 */
object RemoteFileCache {
    private const val CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

    private data class CacheEntry(
        val items: List<RemoteFileManagerController.RemoteFileItem>,
        val etag: String?,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > CACHE_TTL_MS
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val mutex = Mutex()

    /**
     * Get cached directory listing if available and not expired.
     *
     * @param path Directory path
     * @return Cached items or null if not cached/expired
     */
    suspend fun getCached(path: String): List<RemoteFileManagerController.RemoteFileItem>? {
        val entry = cache[path] ?: return null
        return if (entry.isExpired()) {
            // Remove expired entry
            mutex.withLock {
                cache.remove(path)
            }
            null
        } else {
            entry.items
        }
    }

    /**
     * Check if cached data exists and is valid.
     *
     * @param path Directory path
     * @return True if valid cached data exists
     */
    fun hasCached(path: String): Boolean {
        val entry = cache[path] ?: return false
        return !entry.isExpired()
    }

    /**
     * Put directory listing in cache.
     *
     * @param path Directory path
     * @param items List of file items
     * @param directoryEtag Optional ETag for the directory itself
     */
    suspend fun put(
        path: String,
        items: List<RemoteFileManagerController.RemoteFileItem>,
        directoryEtag: String? = null
    ) {
        mutex.withLock {
            cache[path] = CacheEntry(
                items = items,
                etag = directoryEtag
            )
        }
    }

    /**
     * Get cached ETag for a directory.
     *
     * @param path Directory path
     * @return Cached ETag or null
     */
    fun getETag(path: String): String? {
        return cache[path]?.etag
    }

    /**
     * Invalidate cache entry for a specific path.
     *
     * @param path Directory path to invalidate
     */
    suspend fun invalidate(path: String) {
        mutex.withLock {
            cache.remove(path)
        }
    }

    /**
     * Invalidate all cache entries that start with the given path.
     * Useful when a directory is modified/deleted.
     *
     * @param pathPrefix Path prefix to match
     */
    suspend fun invalidateChildren(pathPrefix: String) {
        mutex.withLock {
            val keysToRemove = cache.keys.filter { it.startsWith(pathPrefix) }
            keysToRemove.forEach { cache.remove(it) }
        }
    }

    /**
     * Clear all cached data.
     */
    suspend fun clear() {
        mutex.withLock {
            cache.clear()
        }
    }

    /**
     * Clear all expired entries.
     * Can be called periodically to free memory.
     */
    suspend fun clearExpired() {
        mutex.withLock {
            val keysToRemove = cache.entries
                .filter { it.value.isExpired() }
                .map { it.key }
            keysToRemove.forEach { cache.remove(it) }
        }
    }

    /**
     * Get cache statistics for debugging.
     */
    fun getStats(): CacheStats {
        val now = System.currentTimeMillis()
        var validEntries = 0
        var expiredEntries = 0

        cache.values.forEach { entry ->
            if (entry.isExpired()) {
                expiredEntries++
            } else {
                validEntries++
            }
        }

        return CacheStats(
            totalEntries = cache.size,
            validEntries = validEntries,
            expiredEntries = expiredEntries
        )
    }

    data class CacheStats(
        val totalEntries: Int,
        val validEntries: Int,
        val expiredEntries: Int
    )
}
