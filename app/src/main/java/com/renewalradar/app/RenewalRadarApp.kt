package com.renewalradar.app

import android.app.Application
import com.renewalradar.app.data.AppInstallId
import com.renewalradar.app.data.BackendApiConfig
import com.renewalradar.app.data.BackendPlaidApi
import com.renewalradar.app.data.BankConnectionRepository
import com.renewalradar.app.data.BankSyncRepository
import com.renewalradar.app.data.PlaidBackendApi
import com.renewalradar.app.data.RenewalCandidateRepository
import com.renewalradar.app.data.RenewalDatabase
import com.renewalradar.app.data.RenewalRepository
import com.renewalradar.app.data.SettingsStore
import com.renewalradar.app.data.SubscriptionDetectionRepository
import com.renewalradar.app.notifications.NotificationScheduler

class RenewalRadarApp : Application() {
    lateinit var repository: RenewalRepository
        private set

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var bankConnectionRepository: BankConnectionRepository
        private set

    lateinit var bankSyncRepository: BankSyncRepository
        private set

    lateinit var subscriptionDetectionRepository: SubscriptionDetectionRepository
        private set

    lateinit var renewalCandidateRepository: RenewalCandidateRepository
        private set

    lateinit var appInstallId: String
        private set

    override fun onCreate() {
        super.onCreate()
        val database = RenewalDatabase.get(this)
        appInstallId = AppInstallId.get(this)
        settingsStore = SettingsStore(this)
        val backendApi: PlaidBackendApi = BackendPlaidApi(
            BackendApiConfig(
                baseUrlProvider = { settingsStore.current().bankBackendUrl },
                userId = appInstallId,
                allowLocalHttp = false
            )
        )
        repository = RenewalRepository(database.renewalDao())
        bankConnectionRepository = BankConnectionRepository(backendApi, database.bankDao())
        bankSyncRepository = BankSyncRepository(backendApi, database.bankDao())
        subscriptionDetectionRepository = SubscriptionDetectionRepository()
        renewalCandidateRepository = RenewalCandidateRepository(database.bankDao())
        NotificationScheduler.createChannel(this)
        NotificationScheduler.scheduleDailyChecks(this)
    }
}
