package com.earlyspark.orbn.oura

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import java.security.SecureRandom
import java.util.Base64

/**
 * Drives the front half of the OAuth2 authorization-code flow: build the authorize URL with a
 * fresh anti-CSRF `state` and open it in the system browser. The redirect comes back to
 * [OAuthRedirectActivity], which finishes the exchange.
 *
 * The browser (Custom Tabs, falling back to a plain VIEW intent) is used rather than a WebView:
 * it's the OAuth best practice, keeps orbn out of the user's Oura credentials, and needs no GMS.
 */
object OuraAuthManager {

    /** True if credentials are present; callers should surface a message otherwise. */
    val isConfigured: Boolean get() = OuraConfig.isConfigured

    /**
     * Begin authorization. Returns false (without launching anything) if orbn isn't configured.
     */
    fun startAuthorization(context: Context): Boolean {
        if (!OuraConfig.isConfigured) {
            Log.w(TAG, "startAuthorization called but Oura is not configured")
            return false
        }

        val state = randomState()
        Oura.tokenStore(context).saveAuthState(state)

        val authUri = Uri.parse(OuraConfig.AUTHORIZE_ENDPOINT).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", OuraConfig.clientId)
            .appendQueryParameter("redirect_uri", OuraConfig.redirectUri)
            .appendQueryParameter("scope", OuraConfig.scopeParam)
            .appendQueryParameter("state", state)
            .build()

        launch(context, authUri)
        return true
    }

    private fun launch(context: Context, uri: Uri) {
        try {
            CustomTabsIntent.Builder().build().launchUrl(context, uri)
        } catch (e: ActivityNotFoundException) {
            // No Custom Tabs provider — fall back to whatever browser handles VIEW.
            val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(view)
            } catch (e2: ActivityNotFoundException) {
                Log.e(TAG, "No browser available to complete Oura authorization", e2)
            }
        }
    }

    private fun randomState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private const val TAG = "OrbnOura"
}
