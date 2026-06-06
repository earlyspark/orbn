package com.earlyspark.orbn.oura

import android.content.Context
import com.earlyspark.orbn.BuildConfig
import com.earlyspark.orbn.data.OrbnDatabase

/**
 * Process-wide holder for the Oura singletons (token store, HTTP client, repository). Avoids a
 * DI framework for v1 while keeping a single instance of the token store and OkHttp client.
 */
object Oura {
    @Volatile private var tokenStore: OuraTokenStore? = null
    @Volatile private var apiClient: OuraApiClient? = null
    @Volatile private var repository: OuraRepository? = null

    fun tokenStore(context: Context): OuraTokenStore =
        tokenStore ?: synchronized(this) {
            tokenStore ?: OuraTokenStore(context.applicationContext).also { tokenStore = it }
        }

    fun apiClient(context: Context): OuraApiClient =
        apiClient ?: synchronized(this) {
            apiClient ?: OuraApiClient(
                tokenStore(context),
                enableLogging = BuildConfig.DEBUG,
            ).also { apiClient = it }
        }

    fun repository(context: Context): OuraRepository =
        repository ?: synchronized(this) {
            repository ?: OuraRepository(
                api = apiClient(context),
                dao = OrbnDatabase.get(context).ouraDao(),
                tokenStore = tokenStore(context),
            ).also { repository = it }
        }
}
