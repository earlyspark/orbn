package com.earlyspark.orbn.oura

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists Oura OAuth2 tokens at rest, encrypted via the Android Keystore
 * (EncryptedSharedPreferences, AES-256). Per D10, tokens live here — not in Room.
 *
 * Oura's refresh tokens are **single-use and rotate**: every refresh returns a new refresh
 * token and invalidates the old one. [saveTokens] therefore overwrites both tokens atomically
 * (synchronous `commit()`), so a crash mid-refresh can't leave a stale/lost refresh token.
 *
 * Note: androidx.security:security-crypto is in maintenance mode. It remains the standard,
 * lowest-friction at-rest encryption for SharedPreferences and is sufficient for a single-user
 * app; a future move to DataStore + a hand-rolled Keystore cipher is possible if needed.
 */
class OuraTokenStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val accessToken: String? get() = prefs.getString(KEY_ACCESS, null)
    val refreshToken: String? get() = prefs.getString(KEY_REFRESH, null)

    /** Epoch millis after which the access token is considered expired (0 if none). */
    val expiresAtMillis: Long get() = prefs.getLong(KEY_EXPIRES_AT, 0L)

    val isAuthorized: Boolean get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()

    /**
     * True when the access token is missing or within [SKEW_MILLIS] of expiry, i.e. a refresh
     * should happen before the next call.
     */
    fun needsRefresh(now: Long = System.currentTimeMillis()): Boolean {
        if (accessToken.isNullOrBlank()) return true
        return now >= (expiresAtMillis - SKEW_MILLIS)
    }

    /** Store a freshly issued token set. [expiresInSeconds] is from the token response. */
    fun saveTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long) {
        prefs.edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1000L)
            .commit() // synchronous: never lose the rotated refresh token
    }

    /** Remove all tokens (disconnect / revoke). */
    fun clear() {
        prefs.edit()
            .remove(KEY_ACCESS)
            .remove(KEY_REFRESH)
            .remove(KEY_EXPIRES_AT)
            .commit()
    }

    // --- OAuth CSRF state (the `state` param) -----------------------------------------------

    /** Stash the random `state` issued with the authorize request, to verify on redirect. */
    fun saveAuthState(state: String) {
        prefs.edit().putString(KEY_AUTH_STATE, state).commit()
    }

    /** Read and clear the pending `state` (single-use). */
    fun takeAuthState(): String? {
        val s = prefs.getString(KEY_AUTH_STATE, null)
        if (s != null) prefs.edit().remove(KEY_AUTH_STATE).commit()
        return s
    }

    private companion object {
        const val PREFS_NAME = "orbn_oura_tokens"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_AUTH_STATE = "auth_state"

        /** Refresh this long before the real expiry to avoid racing a 401. */
        const val SKEW_MILLIS = 5 * 60 * 1000L
    }
}
