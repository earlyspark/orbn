package com.earlyspark.orbn.oura

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.earlyspark.orbn.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Catches the `com.earlyspark.orbn://oauth2redirect` callback from the browser, verifies the
 * anti-CSRF `state`, and exchanges the authorization code for tokens. Translucent + no UI — it
 * processes and returns to [MainActivity], which reflects the new connection state on resume.
 */
class OAuthRedirectActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRedirect(intent)
    }

    // singleTask: a re-delivered redirect arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRedirect(intent)
    }

    private fun handleRedirect(intent: Intent?) {
        val data: Uri? = intent?.data
        val tokenStore = Oura.tokenStore(this)
        val expectedState = tokenStore.takeAuthState() // single-use
        val returnedState = data?.getQueryParameter("state")
        val code = data?.getQueryParameter("code")
        val error = data?.getQueryParameter("error")

        when {
            error != null -> finishWith("Oura authorization was denied ($error)")
            code.isNullOrBlank() -> finishWith("No authorization code received")
            expectedState.isNullOrBlank() || returnedState != expectedState ->
                // CSRF guard: reject a redirect we didn't initiate.
                finishWith("Authorization aborted (state mismatch)")
            else -> exchange(code)
        }
    }

    private fun exchange(code: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Oura.apiClient(this@OAuthRedirectActivity).exchangeCode(code) }
            }
            if (result.isSuccess) {
                finishWith("Connected to Oura")
            } else {
                Log.w(TAG, "Token exchange failed", result.exceptionOrNull())
                finishWith("Couldn't connect to Oura — please try again")
            }
        }
    }

    private fun finishWith(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        // Return to the home screen; it re-reads connection state in onResume.
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private companion object {
        const val TAG = "OrbnOura"
    }
}
