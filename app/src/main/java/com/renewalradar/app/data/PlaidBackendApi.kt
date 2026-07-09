package com.renewalradar.app.data

interface PlaidBackendApi {
    suspend fun createLinkToken(): PlaidLinkToken
    suspend fun exchangePublicToken(publicToken: PlaidPublicToken, metadata: PlaidLinkMetadata?): BankConnectResult
    suspend fun exchangePublicToken(publicToken: PlaidPublicToken): List<ConnectedAccountSummary>
    suspend fun syncRecurringTransactions(): BankSyncSummary
    suspend fun disconnectAccount(accountId: String)
}
