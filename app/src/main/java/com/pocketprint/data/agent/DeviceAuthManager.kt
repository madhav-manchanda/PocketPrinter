package com.pocketprint.data.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.pocketprint.data.logging.DiagnosticLogger
import java.util.UUID

/**
 * State of device registration with the remote VPS.
 */
enum class DeviceAuthState {
    UNCONFIGURED,
    CONFIGURED_LOCAL,
    REGISTERED,
    AUTHENTICATED,
    REVOKED,
    AUTH_FAILED
}

/**
 * Secure, Keystore-backed authentication manager for the Android printer-side agent.
 *
 * Security guarantees:
 * - Credentials encrypted at rest using AES-256-GCM backed by Android Keystore.
 * - Device passwords are never logged or exposed in plaintext.
 * - After pairing, authenticates using revocable device-specific cryptographic tokens.
 * - Supports credential rotation, logout, and device revocation.
 */
class DeviceAuthManager(private val context: Context) {

    // Fail closed if Android Keystore-backed encryption is unavailable. Falling back
    // to ordinary SharedPreferences would persist printer passwords and device tokens
    // in plaintext, violating the credential-storage contract.
    private val masterKeyAlias: String =
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val securePrefs: SharedPreferences =
        EncryptedSharedPreferences.create(
            "pocketprint_secure_agent_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    var serverUrl: String
        get() = securePrefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) = securePrefs.edit().putString(KEY_SERVER_URL, value.trim().removeSuffix("/")).apply()

    var deviceId: String
        get() {
            var id = securePrefs.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                val androidId = try {
                    android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        android.provider.Settings.Secure.ANDROID_ID
                    )
                } catch (_: Exception) {
                    null
                }
                id = if (!androidId.isNullOrBlank() && androidId != "9774d56d682e549c") {
                    "android-$androidId"
                } else {
                    UUID.randomUUID().toString()
                }
                securePrefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }
        set(value) = securePrefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var deviceName: String
        get() = securePrefs.getString(KEY_DEVICE_NAME, "${android.os.Build.MODEL} Printer Node") ?: "PocketPrint Node"
        set(value) = securePrefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var deviceToken: String?
        get() = securePrefs.getString(KEY_DEVICE_TOKEN, null)
        set(value) = securePrefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()

    var isPasswordConfigured: Boolean
        get() = securePrefs.getBoolean(KEY_HAS_PASSWORD, false)
        private set(value) = securePrefs.edit().putBoolean(KEY_HAS_PASSWORD, value).apply()

    /**
     * Configures the local device password in encrypted storage.
     */
    fun setDevicePassword(password: String) {
        if (password.length < 6) {
            throw IllegalArgumentException("Device password must be at least 6 characters")
        }
        securePrefs.edit()
            .putString(KEY_DEVICE_PASSWORD, password)
            .putBoolean(KEY_HAS_PASSWORD, true)
            .apply()
        DiagnosticLogger.log("AUTH", "Device password securely saved in Android Keystore")
    }

    /**
     * Retrieves the stored device password for pairing requests.
     */
    fun getDevicePassword(): String? = securePrefs.getString(KEY_DEVICE_PASSWORD, null)

    /** Remove the one-time pairing code after successful registration. */
    fun clearDevicePassword() {
        securePrefs.edit()
            .remove(KEY_DEVICE_PASSWORD)
            .putBoolean(KEY_HAS_PASSWORD, false)
            .apply()
    }

    /**
     * Determines current operational authentication state.
     */
    fun getAuthState(): DeviceAuthState {
        val url = serverUrl
        val hasPwd = isPasswordConfigured
        val token = deviceToken

        return when {
            url.isBlank() -> DeviceAuthState.UNCONFIGURED
            securePrefs.getBoolean(KEY_IS_REVOKED, false) -> DeviceAuthState.REVOKED
            token != null -> DeviceAuthState.AUTHENTICATED
            hasPwd -> DeviceAuthState.CONFIGURED_LOCAL
            else -> DeviceAuthState.UNCONFIGURED
        }
    }

    fun markRevoked() {
        securePrefs.edit()
            .putBoolean(KEY_IS_REVOKED, true)
            .remove(KEY_DEVICE_TOKEN)
            .apply()
        DiagnosticLogger.log("AUTH", "Device marked as REVOKED by server")
    }

    fun clearAuthentication() {
        securePrefs.edit()
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_IS_REVOKED)
            .apply()
        DiagnosticLogger.log("AUTH", "Device session cleared")
    }

    fun fullReset() {
        securePrefs.edit().clear().apply()
        DiagnosticLogger.log("AUTH", "Device full reset executed")
    }

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_PASSWORD = "device_password_encrypted"
        private const val KEY_HAS_PASSWORD = "has_password"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_IS_REVOKED = "is_revoked"
    }
}
