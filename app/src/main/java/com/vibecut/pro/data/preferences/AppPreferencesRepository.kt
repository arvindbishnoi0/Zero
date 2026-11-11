package com.vibecut.pro.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vibecut_pro_settings")

data class StudioPreferences(
    val onboardingCompleted: Boolean = false,
    val autosaveIntervalSec: Int = 8,
    val lowEndProxyPreview: Boolean = false,
    val defaultResolutionName: String = "FHD_1080P",
    val defaultFrameRateName: String = "FPS_30",
    val externalAiEndpointUrl: String = "",
    val externalAiApiKey: String = ""
)

@Singleton
class AppPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val AUTOSAVE_SEC = intPreferencesKey("autosave_sec")
        val PROXY_PREVIEW = booleanPreferencesKey("proxy_preview")
        val DEFAULT_RES = stringPreferencesKey("default_res")
        val DEFAULT_FPS = stringPreferencesKey("default_fps")
        val AI_ENDPOINT = stringPreferencesKey("ai_endpoint")
        val AI_KEY = stringPreferencesKey("ai_key")
    }

    val preferencesFlow: Flow<StudioPreferences> = context.dataStore.data.map { prefs ->
        StudioPreferences(
            onboardingCompleted = prefs[Keys.ONBOARDING_DONE] ?: false,
            autosaveIntervalSec = prefs[Keys.AUTOSAVE_SEC] ?: 8,
            lowEndProxyPreview = prefs[Keys.PROXY_PREVIEW] ?: false,
            defaultResolutionName = prefs[Keys.DEFAULT_RES] ?: "FHD_1080P",
            defaultFrameRateName = prefs[Keys.DEFAULT_FPS] ?: "FPS_30",
            externalAiEndpointUrl = prefs[Keys.AI_ENDPOINT] ?: "",
            externalAiApiKey = prefs[Keys.AI_KEY] ?: ""
        )
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = completed }
    }

    suspend fun setAutosaveIntervalSec(seconds: Int) {
        context.dataStore.edit { it[Keys.AUTOSAVE_SEC] = seconds.coerceIn(3, 60) }
    }

    suspend fun setLowEndProxyPreview(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PROXY_PREVIEW] = enabled }
    }

    suspend fun setDefaultExport(resolutionName: String, frameRateName: String) {
        context.dataStore.edit {
            it[Keys.DEFAULT_RES] = resolutionName
            it[Keys.DEFAULT_FPS] = frameRateName
        }
    }

    suspend fun setExternalAiConfig(endpointUrl: String, apiKey: String) {
        context.dataStore.edit {
            it[Keys.AI_ENDPOINT] = endpointUrl.trim()
            it[Keys.AI_KEY] = apiKey.trim()
        }
    }
}
