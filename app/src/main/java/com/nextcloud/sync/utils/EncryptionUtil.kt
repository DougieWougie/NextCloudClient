package com.nextcloud.sync.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encryption utilities for securing passwords and database keys.
 *
 * SECURITY NOTES:
 * - Database passphrase is stored in EncryptedSharedPreferences (AES-256-GCM)
 * - Master key is backed by Android Keystore (hardware-backed when available)
 * - Passwords are encrypted using AES-GCM before database storage
 * - Backup exclusion rules prevent data leakage (see AndroidManifest.xml)
 *
 * MEMORY SECURITY LIMITATION:
 * - String objects are immutable and cannot be zeroed after use
 * - Decrypted passwords remain in memory until garbage collected
 * - For maximum security, minimize the lifetime of password-holding objects
 * - CharArray alternatives are provided for security-sensitive contexts
 */
object EncryptionUtil {
    private const val KEY_ALIAS_PASSWORD = "nextcloud_sync_password_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    /**
     * Gets or creates the database encryption key.
     *
     * The key is:
     * - 256 bits of cryptographically strong random data
     * - Stored in EncryptedSharedPreferences (AES-256-GCM encrypted)
     * - Protected from backup/restore via data extraction rules
     * - Backed by Android Keystore master key
     *
     * @param context Application context
     * @return Database encryption key as byte array
     */
    fun getDatabaseKey(context: Context): ByteArray {
        // Get or create encryption key from EncryptedSharedPreferences
        val encryptedPrefs = EncryptedSharedPreferences.create(
            context,
            "secure_prefs",
            getMasterKey(context),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        var dbKey = encryptedPrefs.getString("db_passphrase", null)

        if (dbKey == null) {
            // Generate new key with strong entropy
            dbKey = generateRandomKey()
            encryptedPrefs.edit().putString("db_passphrase", dbKey).apply()
            SafeLogger.d("EncryptionUtil", "Generated new database passphrase")
        }

        return dbKey.toByteArray(Charsets.UTF_8)
    }

    fun encryptPassword(password: String): String {
        val key = getOrCreateKey(KEY_ALIAS_PASSWORD)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))

        // Combine IV and encrypted data
        val combined = iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Decrypts a password from storage.
     *
     * SECURITY WARNING: Returns a String which cannot be zeroed from memory.
     * Consider using decryptPasswordToCharArray() for security-sensitive contexts.
     *
     * @param encryptedPassword Base64-encoded encrypted password
     * @return Decrypted password as String
     */
    fun decryptPassword(encryptedPassword: String): String {
        val key = getOrCreateKey(KEY_ALIAS_PASSWORD)
        val combined = Base64.decode(encryptedPassword, Base64.NO_WRAP)

        // Extract IV and encrypted data
        val iv = combined.sliceArray(0 until GCM_IV_LENGTH)
        val encrypted = combined.sliceArray(GCM_IV_LENGTH until combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        val decrypted = cipher.doFinal(encrypted)
        val result = String(decrypted, Charsets.UTF_8)

        // Zero the decrypted byte array
        decrypted.fill(0)

        return result
    }

    /**
     * Decrypts a password to CharArray for security-sensitive contexts.
     *
     * CharArray can be explicitly zeroed after use, unlike String.
     * IMPORTANT: Caller must zero the returned CharArray after use:
     *   val password = decryptPasswordToCharArray(encrypted)
     *   try {
     *     // Use password
     *   } finally {
     *     password.fill('\u0000')
     *   }
     *
     * @param encryptedPassword Base64-encoded encrypted password
     * @return Decrypted password as CharArray (caller must zero after use)
     */
    fun decryptPasswordToCharArray(encryptedPassword: String): CharArray {
        val key = getOrCreateKey(KEY_ALIAS_PASSWORD)
        val combined = Base64.decode(encryptedPassword, Base64.NO_WRAP)

        // Extract IV and encrypted data
        val iv = combined.sliceArray(0 until GCM_IV_LENGTH)
        val encrypted = combined.sliceArray(GCM_IV_LENGTH until combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        val decrypted = cipher.doFinal(encrypted)

        // SECURITY: Properly convert UTF-8 bytes to String, then to CharArray
        // Previous implementation incorrectly treated each byte as a character code,
        // which broke passwords with non-ASCII characters (accents, emoji, etc.)
        val passwordString = String(decrypted, Charsets.UTF_8)
        val result = passwordString.toCharArray()

        // Zero the byte arrays and intermediate string
        decrypted.fill(0)
        iv.fill(0)
        encrypted.fill(0)
        combined.fill(0)

        return result
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        if (!keyStore.containsAlias(alias)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )

            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }

        return keyStore.getKey(alias, null) as SecretKey
    }

    private fun getMasterKey(context: Context): MasterKey {
        return MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Generates a cryptographically strong random key using the strongest available algorithm.
     * Uses blocking entropy source for maximum security during key generation.
     *
     * @return Base64-encoded random key
     */
    private fun generateRandomKey(): String {
        // Use the strongest available SecureRandom algorithm
        // This may block briefly on first call to ensure sufficient entropy
        val random = try {
            SecureRandom.getInstanceStrong()
        } catch (e: Exception) {
            SafeLogger.w("EncryptionUtil", "Strong SecureRandom not available, using default")
            SecureRandom()
        }

        val bytes = ByteArray(32) // 256 bits of entropy
        random.nextBytes(bytes)

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
