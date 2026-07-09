package com.renewalradar.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RenewalSettings(
    val defaultRenewWindowDays: Int = 70,
    val defaultAttentionWindowDays: Int = 14,
    val notificationsEnabled: Boolean = true,
    val darkModeEnabled: Boolean = false,
    val firstLaunchSetupComplete: Boolean = false
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("renewal_settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val settings: StateFlow<RenewalSettings> = state.asStateFlow()

    fun update(settings: RenewalSettings) {
        prefs.edit()
            .putInt(KEY_RENEW, settings.defaultRenewWindowDays)
            .putInt(KEY_ATTENTION, settings.defaultAttentionWindowDays)
            .putBoolean(KEY_NOTIFICATIONS, settings.notificationsEnabled)
            .putBoolean(KEY_DARK_MODE, settings.darkModeEnabled)
            .putBoolean(KEY_FIRST_LAUNCH_SETUP_COMPLETE, settings.firstLaunchSetupComplete)
            .apply()
        state.value = settings
    }

    private fun read() = RenewalSettings(
        defaultRenewWindowDays = prefs.getInt(KEY_RENEW, 70),
        defaultAttentionWindowDays = prefs.getInt(KEY_ATTENTION, 14),
        notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, true),
        darkModeEnabled = prefs.getBoolean(KEY_DARK_MODE, false),
        firstLaunchSetupComplete = prefs.getBoolean(KEY_FIRST_LAUNCH_SETUP_COMPLETE, false)
    )

    private companion object {
        const val KEY_RENEW = "default_renew_window_days"
        const val KEY_ATTENTION = "default_attention_window_days"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_DARK_MODE = "dark_mode_enabled"
        const val KEY_FIRST_LAUNCH_SETUP_COMPLETE = "first_launch_setup_complete"
    }
}
