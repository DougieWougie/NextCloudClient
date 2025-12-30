package com.nextcloud.sync.models.data

/**
 * Enum representing different types of 2FA providers.
 */
enum class TwoFactorProviderType {
    TOTP,           // Time-based One-Time Password (6-digit codes)
    NOTIFICATION,   // Nextcloud notification-based approval
    WEBAUTHN,       // WebAuthn/FIDO2 authentication
    U2F,            // Universal 2nd Factor (older standard)
    BACKUP_CODES,   // Backup recovery codes
    UNKNOWN;        // Unknown or unsupported provider

    companion object {
        /**
         * Map Nextcloud provider ID to our enum.
         * Provider IDs come from the server's 2FA challenge response.
         */
        fun fromProviderId(id: String): TwoFactorProviderType {
            return when (id.lowercase()) {
                "totp" -> TOTP
                "twofactor_nextcloud_notification", "notification" -> NOTIFICATION
                "webauthn" -> WEBAUTHN
                "u2f" -> U2F
                "backup_codes", "backupcodes" -> BACKUP_CODES
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Data class representing a 2FA provider available for an account.
 *
 * @param id Provider ID from the server (e.g., "totp", "twofactor_nextcloud_notification")
 * @param displayName Human-readable name (e.g., "Authenticator app", "Notification")
 * @param type Mapped provider type
 */
data class TwoFactorProvider(
    val id: String,
    val displayName: String,
    val type: TwoFactorProviderType
) {
    /**
     * Whether this provider is supported by the app.
     * Currently only TOTP and NOTIFICATION are fully supported.
     */
    val isSupported: Boolean
        get() = type == TwoFactorProviderType.TOTP || type == TwoFactorProviderType.NOTIFICATION
}
