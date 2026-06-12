package com.earlyspark.orbn.oura

import okhttp3.Authenticator
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Base64

/** Thrown when the API returns a non-2xx (and non-401-handled) response. */
class OuraApiException(val code: Int, message: String) : IOException("Oura API $code: $message")

/** Thrown when orbn isn't configured (no client credentials) or the user isn't connected. */
class OuraAuthException(message: String) : IOException(message)

/**
 * Thin HTTP client for the Oura OAuth token endpoint and the v2 `usercollection` endpoints.
 *
 * Token handling:
 *  - **Proactive refresh:** an interceptor refreshes the access token before a call if it's
 *    near expiry, then attaches `Authorization: Bearer …`.
 *  - **Reactive refresh:** an [Authenticator] refreshes once on a 401 and retries.
 *  - Oura refresh tokens are **single-use**; both paths refresh under one lock and re-check
 *    state inside it, so two concurrent calls can never spend the same refresh token twice.
 *
 * The token endpoint authenticates with HTTP Basic (client_id:client_secret) so the secret
 * never lands in a request body or query string.
 */
class OuraApiClient(
    private val tokenStore: OuraTokenStore,
    enableLogging: Boolean = false,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val refreshLock = Any()

    private val loggingEnabled = enableLogging

    /** Bare client used only for the token endpoint (no bearer interceptor → no recursion). */
    private val tokenClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .apply { if (loggingEnabled) addInterceptor(loggingInterceptor()) }
            .build()
    }

    /** Authenticated client for API calls (lazy so the interceptor vals exist first). */
    private val apiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(bearerInterceptor)
            .authenticator(refreshAuthenticator)
            .apply { if (loggingEnabled) addInterceptor(loggingInterceptor()) }
            .build()
    }

    // --- OAuth ------------------------------------------------------------------------------

    /** Exchange an authorization code for tokens and persist them. */
    fun exchangeCode(code: String) {
        requireConfigured()
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", OuraConfig.redirectUri)
            .build()
        val token = postToken(body) ?: throw OuraApiException(0, "empty token response")
        // A code exchange immediately follows consent, so when the response omits `scope`
        // the grant is whatever was just requested.
        persist(token, fallbackScope = OuraConfig.scopeParam)
    }

    /**
     * Refresh using the stored refresh token. Serialized via [refreshLock] and safe to call
     * redundantly. Returns true on success.
     */
    private fun refreshBlocking(): Boolean {
        val current = tokenStore.refreshToken ?: return false
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", current)
            .build()
        val token = postToken(body) ?: return false
        persist(token)
        return true
    }

    private fun postToken(body: FormBody): TokenResponse? {
        val req = Request.Builder()
            .url(OuraConfig.TOKEN_ENDPOINT)
            .header("Authorization", basicAuth())
            .post(body)
            .build()
        tokenClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string()
            if (!resp.isSuccessful) {
                throw OuraApiException(resp.code, raw?.take(300) ?: resp.message)
            }
            if (raw.isNullOrBlank()) return null
            return json.decodeFromString(TokenResponse.serializer(), raw)
        }
    }

    private fun persist(token: TokenResponse, fallbackScope: String? = null) {
        val access = token.accessToken
        val refresh = token.refreshToken
        if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
            throw OuraApiException(0, "token response missing access/refresh token")
        }
        // expires_in occasionally absent → fall back to Oura's documented ~30-day lifetime.
        // Scope: prefer the server's echo; null at refresh time keeps the recorded grant.
        tokenStore.saveTokens(
            access,
            refresh,
            token.expiresIn ?: DEFAULT_EXPIRES_IN,
            scope = token.scope ?: fallbackScope,
        )
    }

    // --- Endpoints --------------------------------------------------------------------------

    fun getDailyReadiness(startDate: String, endDate: String): List<DailyReadiness> =
        getCollection("daily_readiness", "start_date" to startDate, "end_date" to endDate)

    fun getDailySleep(startDate: String, endDate: String): List<DailySleep> =
        getCollection("daily_sleep", "start_date" to startDate, "end_date" to endDate)

    fun getSleepPeriods(startDate: String, endDate: String): List<SleepPeriod> =
        getCollection("sleep", "start_date" to startDate, "end_date" to endDate)

    fun getSessions(startDate: String, endDate: String): List<Session> =
        getCollection("session", "start_date" to startDate, "end_date" to endDate)

    fun getDailyActivity(startDate: String, endDate: String): List<DailyActivity> =
        getCollection("daily_activity", "start_date" to startDate, "end_date" to endDate)

    fun getDailyStress(startDate: String, endDate: String): List<DailyStress> =
        getCollection("daily_stress", "start_date" to startDate, "end_date" to endDate)

    fun getHeartRate(startDateTime: String, endDateTime: String): List<HeartRateSample> =
        getCollection(
            "heartrate",
            "start_datetime" to startDateTime,
            "end_datetime" to endDateTime,
        )

    /**
     * GET a paginated `usercollection` endpoint, following `next_token` up to [MAX_PAGES].
     * Reified so the envelope element type is known to the serializer.
     */
    private inline fun <reified T> getCollection(
        path: String,
        vararg params: Pair<String, String>,
    ): List<T> {
        requireAuthorized()
        val out = ArrayList<T>()
        var nextToken: String? = null
        var pages = 0
        do {
            val builder = (OuraConfig.API_BASE + "v2/usercollection/$path").toHttpUrl().newBuilder()
            params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
            nextToken?.let { builder.addQueryParameter("next_token", it) }
            val req = Request.Builder().url(builder.build()).get().build()
            apiClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string()
                if (!resp.isSuccessful) {
                    throw OuraApiException(resp.code, raw?.take(300) ?: resp.message)
                }
                if (!raw.isNullOrBlank()) {
                    val env = json.decodeFromString<OuraEnvelope<T>>(raw)
                    out.addAll(env.data)
                    nextToken = env.nextToken
                } else {
                    nextToken = null
                }
            }
        } while (nextToken != null && ++pages < MAX_PAGES)
        return out
    }

    // --- Token plumbing ---------------------------------------------------------------------

    private val bearerInterceptor = Interceptor { chain ->
        if (tokenStore.needsRefresh()) ensureFreshToken()
        val token = tokenStore.accessToken
        val req = chain.request().newBuilder()
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .build()
        chain.proceed(req)
    }

    private val refreshAuthenticator = Authenticator { _, response ->
        if (responseCount(response) >= 2) return@Authenticator null // already retried once
        val failedAuth = response.request.header("Authorization")
        val newAuth = synchronized(refreshLock) {
            val current = tokenStore.accessToken?.let { "Bearer $it" }
            when {
                // Another call already refreshed while we waited on the lock.
                current != null && current != failedAuth -> current
                refreshBlocking() -> tokenStore.accessToken?.let { "Bearer $it" }
                else -> null
            }
        }
        newAuth?.let { response.request.newBuilder().header("Authorization", it).build() }
    }

    /** Refresh only if still needed once the lock is held (avoids redundant single-use spend). */
    private fun ensureFreshToken() {
        synchronized(refreshLock) {
            if (tokenStore.needsRefresh()) refreshBlocking()
        }
    }

    private fun basicAuth(): String {
        val raw = "${OuraConfig.clientId}:${OuraConfig.clientSecret}"
        return "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    private fun requireConfigured() {
        if (!OuraConfig.isConfigured) throw OuraAuthException("Oura is not configured")
    }

    private fun requireAuthorized() {
        requireConfigured()
        if (!tokenStore.isAuthorized) throw OuraAuthException("Oura is not connected")
    }

    private fun loggingInterceptor() = HttpLoggingInterceptor().apply {
        // BASIC only: never log headers/bodies, which would expose the bearer token.
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        const val MAX_PAGES = 10
        const val DEFAULT_EXPIRES_IN = 30L * 24 * 60 * 60 // ~30 days, per Oura docs
    }
}
