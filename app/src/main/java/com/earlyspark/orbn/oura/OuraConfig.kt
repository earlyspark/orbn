package com.earlyspark.orbn.oura

import com.earlyspark.orbn.BuildConfig

/**
 * Static Oura OAuth2 configuration.
 *
 * Credentials are injected from `local.properties` (gitignored) via `BuildConfig` — they are
 * never hardcoded here or anywhere in source. When they're absent the fields are empty strings
 * and [isConfigured] is false; callers must surface "Oura not configured" rather than attempt a
 * request with blank credentials.
 *
 * Endpoints and the scope list come from the canonical Oura docs (cloud.ouraring.com/docs).
 */
object OuraConfig {
    val clientId: String = BuildConfig.OURA_CLIENT_ID
    val clientSecret: String = BuildConfig.OURA_CLIENT_SECRET
    val redirectUri: String = BuildConfig.OURA_REDIRECT_URI

    const val AUTHORIZE_ENDPOINT = "https://cloud.ouraring.com/oauth/authorize"
    const val TOKEN_ENDPOINT = "https://api.ouraring.com/oauth/token"
    const val API_BASE = "https://api.ouraring.com/"

    /**
     * Requested scopes — must stay a subset of what's enabled on the Oura app registration.
     * `personal` (age/sex) and `workout` are intentionally excluded: age isn't used as a matching
     * input, and workout is an event-only summary with no live signal (amends D19's grab-all).
     * Changing scopes forces a re-consent, so the rest are kept even where not yet consumed.
     */
    val scopes: List<String> = listOf(
        "email",
        "daily",
        "heartrate",
        "tag",
        "session",
        "spo2",
    )

    val scopeParam: String get() = scopes.joinToString(" ")

    /** True only when all three credential fields were supplied at build time. */
    val isConfigured: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank() && redirectUri.isNotBlank()
}
