package com.renewalradar.app

import android.app.Application
import com.renewalradar.app.data.RenewalDatabase
import com.renewalradar.app.data.RenewalRepository
import com.renewalradar.app.data.SettingsStore
import com.renewalradar.app.notifications.NotificationScheduler

class RenewalRadarApp : Application() {
    lateinit var repository: RenewalRepository
        private set

    lateinit var settingsStore: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        repository = RenewalRepository(RenewalDatabase.get(this).renewalDao())
        settingsStore = SettingsStore(this)
        NotificationScheduler.createChannel(this)
        NotificationScheduler.scheduleDailyChecks(this)
    }
}
