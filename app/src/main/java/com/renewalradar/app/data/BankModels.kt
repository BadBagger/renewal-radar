package com.renewalradar.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

enum class BankConnectionStatus {
    NotConnected,
    Connecting,
    Connected,
    Syncing,
    SyncFailed,
    Disconnected,
    PermissionRevoked
}

@Entity(tableName = "connected_accounts")
data class ConnectedAccount(
    @PrimaryKey val accountId: String,
    val institutionName: String,
    val accountName: String,
    val accountMask: String,
    val accountType: String,
    val status: BankConnectionStatus = BankConnectionStatus.Connected,
    val lastSyncedAtMillis: Long? = null
)

enum class CandidateStatus {
    Pending,
    Confirmed,
    Ignored,
    Dismissed
}

@Entity(tableName = "renewal_candidates")
data class RenewalCandidate(
    @PrimaryKey val candidateId: String,
    val merchantName: String,
    val amountCents: Int,
    val cadence: String,
    val nextChargeDate: LocalDate,
    val sourceAccountId: String,
    val confidence: Float,
    val status: CandidateStatus = CandidateStatus.Pending,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val averageAmountCents: Int = amountCents,
    val lastChargeDate: LocalDate = nextChargeDate,
    val transactionsUsed: Int = 0,
    val accountNickname: String = "",
    val category: String = "",
    val reasonDetected: String = "",
    val candidateType: String = "subscription",
    val matchingTransactions: String = "",
    val reminderDays: Int = 14,
    val notes: String = "",
    val amountVarianceCents: Int = 0,
    val nextChargeWindowStart: LocalDate = nextChargeDate,
    val nextChargeWindowEnd: LocalDate = nextChargeDate,
    val userEditedNextDate: Boolean = false,
    val inactive: Boolean = false,
    val watchOuts: String = ""
)

data class PlaidLinkToken(
    val token: String,
    val expirationMillis: Long
)

data class PlaidPublicToken(
    val value: String
)

data class PlaidInstitutionMetadata(
    val id: String?,
    val name: String?
)

data class PlaidAccountMetadata(
    val id: String,
    val name: String,
    val mask: String?,
    val type: String,
    val subtype: String?
)

data class PlaidLinkMetadata(
    val institution: PlaidInstitutionMetadata?,
    val accounts: List<PlaidAccountMetadata>
)

data class BankConnectResult(
    val accounts: List<ConnectedAccountSummary>
)

data class ConnectedAccountSummary(
    val accountId: String,
    val institutionName: String,
    val accountName: String,
    val accountMask: String,
    val accountType: String
)

data class BankSyncSummary(
    val accounts: List<ConnectedAccountSummary>,
    val candidates: List<RenewalCandidate>,
    val syncedAtMillis: Long
)

data class TransactionSummary(
    val transactionId: String = "",
    val accountId: String,
    val date: LocalDate,
    val authorizedDate: LocalDate? = null,
    val merchantName: String,
    val originalDescription: String = merchantName,
    val amountCents: Int,
    val currency: String = "USD",
    val category: String = "",
    val paymentChannel: String = "",
    val pending: Boolean = false,
    val logoUrl: String? = null,
    val website: String? = null,
    val accountNickname: String = ""
)
