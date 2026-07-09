package com.renewalradar.app.data

import android.content.Context
import java.util.UUID

object AppInstallId {
    private const val PREFS = "renewal_install"
    private const val KEY_INSTALL_ID = "install_id"

    fun get(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_INSTALL_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val created = "rr-${UUID.randomUUID()}"
        prefs.edit().putString(KEY_INSTALL_ID, created).apply()
        return created
    }
}
