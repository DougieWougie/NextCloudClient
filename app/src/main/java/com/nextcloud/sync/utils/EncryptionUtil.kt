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

object EncryptionUtil {
    private const val KEY_ALIAS_PASSWORD = "nextcloud_sync_password_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

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
            // Generate new key
            dbKey = generateRandomKey()
            encryptedPrefs.edit().putString("db_passphrase", dbKey).apply()
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
        return String(decrypted, Charsets.UTF_8)
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

    private fun generateRandomKey(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
