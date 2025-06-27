package me.ash.reader.domain.model.account.security

class NextcloudNewsSecurityKey private constructor() : SecurityKey() {

    var serverUrl: String? = null
    var username: String? = null
    var password: String? = null
    var clientCertificateAlias: String? = null // For consistency, though Nextcloud News API uses Basic Auth

    constructor(
        serverUrl: String?,
        username: String?,
        password: String?,
        clientCertificateAlias: String? = null
    ) : this() {
        this.serverUrl = serverUrl
        this.username = username
        this.password = password
        this.clientCertificateAlias = clientCertificateAlias
    }

    /**
     * Decodes a DES-encrypted string into a NextcloudNewsSecurityKey object.
     * The encrypted string is expected to be a JSON representation of the key's properties.
     */
    constructor(value: String? = DESUtils.empty) : this() {
        // Ensure that if value is null or effectively empty after decryption,
        // we initialize with default (null) properties.
        if (value == null || value == DESUtils.empty) {
            // Initialize with nulls, which is default constructor behavior already
            return
        }
        try {
            decode(value, NextcloudNewsSecurityKey::class.java).let {
                this.serverUrl = it.serverUrl
                this.username = it.username
                this.password = it.password
                this.clientCertificateAlias = it.clientCertificateAlias
            }
        } catch (e: Exception) {
            // Log error or handle appropriately if decoding fails
            // For now, defaults to null properties if there's any issue.
            // This matches the behavior if 'value' was initially DESUtils.empty
            // Consider logging e.g. Timber.e(e, "Failed to decode NextcloudNewsSecurityKey")
        }
    }
}
