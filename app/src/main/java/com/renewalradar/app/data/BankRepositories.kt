package com.renewalradar.app.data

import kotlinx.coroutines.flow.Flow

class BankConnectionRepository(
    private val backendApi: PlaidBackendApi,
    private val bankDao: BankDao
) {
    val connectedAccounts: Flow<List<ConnectedAccount>> = bankDao.observeConnectedAccounts()

    suspend fun createLinkToken(): PlaidLinkToken = backendApi.createLinkToken()

    suspend fun exchangePublicToken(publicToken: PlaidPublicToken, metadata: PlaidLinkMetadata?) {
        val accounts = backendApi.exchangePublicToken(publicToken, metadata)
            .accounts
            .map { it.toEntity(lastSyncedAtMillis = null, status = BankConnectionStatus.Connected) }
        bankDao.deleteAccountById(CONNECTING_PLACEHOLDER_ID)
        bankDao.upsertAccounts(accounts)
    }

    suspend fun markConnectingPlaceholder() {
        bankDao.upsertAccounts(
            listOf(
                ConnectedAccount(
                    accountId = CONNECTING_PLACEHOLDER_ID,
                    institutionName = "Plaid Link",
                    accountName = "Connecting bank or card",
                    accountMask = "",
                    accountType = "Secure connection",
                    status = BankConnectionStatus.Connecting
                )
            )
        )
    }

    suspend fun clearConnectingPlaceholder() {
        bankDao.deleteAccountById(CONNECTING_PLACEHOLDER_ID)
    }

    suspend fun connectDemoAccount() {
        bankDao.deleteAccountById(CONNECTING_PLACEHOLDER_ID)
        bankDao.upsertAccounts(
            listOf(
                ConnectedAccount(
                    accountId = DEMO_ACCOUNT_ID,
                    institutionName = "Demo Bank",
                    accountName = "Everyday Checking",
                    accountMask = "0000",
                    accountType = "checking",
                    status = BankConnectionStatus.Connected,
                    lastSyncedAtMillis = System.currentTimeMillis()
                )
            )
        )
    }

    suspend fun disconnect(accountId: String) {
        backendApi.disconnectAccount(accountId)
        bankDao.markDisconnected(accountId)
    }

    companion object {
        const val CONNECTING_PLACEHOLDER_ID = "connecting-placeholder"
        const val DEMO_ACCOUNT_ID = "demo-checking"
    }
}

class BankSyncRepository(
    private val backendApi: PlaidBackendApi,
    private val bankDao: BankDao
) {
    suspend fun syncNow() {
        bankDao.markAllConnectedSyncing()
        val result = backendApi.syncRecurringTransactions()
        if (result.accounts.isNotEmpty()) {
            bankDao.upsertAccounts(
                result.accounts.map {
                    it.toEntity(
                        lastSyncedAtMillis = result.syncedAtMillis,
                        status = BankConnectionStatus.Connected
                    )
                }
            )
        } else {
            bankDao.markAllConnectedSynced(result.syncedAtMillis)
        }
        val backend = backendApi as? BackendPlaidApi
        val incomingCandidates = result.candidates + backend?.getCandidates().orEmpty()
        val existing = if (incomingCandidates.isEmpty()) {
            emptyMap()
        } else {
            bankDao.getCandidatesByIds(incomingCandidates.map { it.candidateId })
                .associateBy { it.candidateId }
        }
        bankDao.upsertCandidates(
            incomingCandidates.map { incoming ->
                val local = existing[incoming.candidateId]
                if (local?.userEditedNextDate == true) {
                    incoming.copy(
                        nextChargeDate = local.nextChargeDate,
                        nextChargeWindowStart = local.nextChargeDate,
                        nextChargeWindowEnd = local.nextChargeDate,
                        userEditedNextDate = true,
                        reminderDays = local.reminderDays,
                        notes = local.notes,
                        status = local.status
                    )
                } else {
                    incoming
                }
            }
        )
    }

    suspend fun markSyncFailed() {
        bankDao.markSyncingFailed()
    }

    suspend fun syncDemoCandidates() {
        val syncedAt = System.currentTimeMillis()
        bankDao.upsertAccounts(
            listOf(
                ConnectedAccount(
                    accountId = BankConnectionRepository.DEMO_ACCOUNT_ID,
                    institutionName = "Demo Bank",
                    accountName = "Everyday Checking",
                    accountMask = "0000",
                    accountType = "checking",
                    status = BankConnectionStatus.Connected,
                    lastSyncedAtMillis = syncedAt
                )
            )
        )
        bankDao.upsertCandidates(
            SubscriptionDetectionRepository().detectRecurringCandidates(DemoTransactionSeed.transactions())
        )
    }

    suspend fun importTransactions(transactions: List<TransactionSummary>): Int {
        val syncedAt = System.currentTimeMillis()
        val accountIds = transactions.map { it.accountId }.distinct().ifEmpty { listOf("csv-import") }
        bankDao.upsertAccounts(
            accountIds.map { accountId ->
                ConnectedAccount(
                    accountId = accountId,
                    institutionName = "CSV Import",
                    accountName = transactions.firstOrNull { it.accountId == accountId }?.accountNickname?.ifBlank { "Imported account" }
                        ?: "Imported account",
                    accountMask = "",
                    accountType = "imported",
                    status = BankConnectionStatus.Connected,
                    lastSyncedAtMillis = syncedAt
                )
            }
        )
        val candidates = SubscriptionDetectionRepository().detectRecurringCandidates(transactions)
        bankDao.upsertCandidates(candidates)
        return candidates.size
    }
}

class SubscriptionDetectionRepository {
    fun detectRecurringCandidates(transactions: List<TransactionSummary>): List<RenewalCandidate> {
        return transactions
            .filter { !it.pending && it.amountCents > 0 && !isIncome(it) }
            .groupBy { it.accountId to normalizeMerchant(it.merchantName.ifBlank { it.originalDescription }) }
            .flatMap { (key, merchantTransactions) ->
                clusterBySimilarAmount(merchantTransactions).mapNotNull { cluster ->
                    buildCandidate(key.first, key.second, cluster)
                }
            }
            .sortedWith(compareByDescending<RenewalCandidate> { it.confidence }.thenBy { it.nextChargeDate })
    }

    private fun buildCandidate(
        accountId: String,
        normalizedMerchant: String,
        transactions: List<TransactionSummary>
    ): RenewalCandidate? {
        if (transactions.size < 2) return null
        val sorted = transactions.sortedBy { it.date }
        val gaps = sorted.zipWithNext { first, second -> second.date.toEpochDay() - first.date.toEpochDay() }
        val cadence = classifyCadence(sorted, gaps)
        if (cadence == null && transactions.size < 3) return null

        val averageGap = gaps.takeIf { it.isNotEmpty() }?.average()?.coerceAtLeast(1.0) ?: 30.0
        val latest = sorted.last()
        val averageAmount = sorted.map { it.amountCents }.average().toInt()
        val amountVariance = amountSpread(sorted)
        val stableAmount = amountSpread(sorted) <= stableTolerance(averageAmount)
        val confidence = confidenceFor(sorted.size, cadence, stableAmount)
        val candidateType = classifyCandidateType(sorted)
        val predictedDate = latest.date.plusDays(predictedGapDays(cadence, averageGap))
        val predictionWindow = predictionWindow(predictedDate, cadence, candidateType, stableAmount)
        val watchOuts = watchOuts(sorted, predictedDate, predictionWindow, averageAmount)
        val reason = buildReason(sorted.size, cadence ?: "Irregular but repeated", stableAmount, candidateType)

        return RenewalCandidate(
            candidateId = "cand-${accountId}-${normalizedMerchant}-${averageAmount / 100}"
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-'),
            merchantName = latest.merchantName.ifBlank { latest.originalDescription },
            amountCents = averageAmount,
            averageAmountCents = averageAmount,
            cadence = cadence ?: "Irregular but repeated",
            nextChargeDate = predictedDate,
            lastChargeDate = latest.date,
            sourceAccountId = accountId,
            confidence = confidence,
            transactionsUsed = sorted.size,
            accountNickname = latest.accountNickname,
            category = latest.category,
            reasonDetected = reason,
            candidateType = candidateType,
            matchingTransactions = sorted.takeLast(3).joinToString("\n") {
                "${it.date} ${formatCents(it.amountCents)} ${it.merchantName.ifBlank { it.originalDescription }}"
            },
            amountVarianceCents = amountVariance,
            nextChargeWindowStart = predictionWindow.first,
            nextChargeWindowEnd = predictionWindow.second,
            inactive = watchOuts.any { it == "Subscription not seen recently" },
            watchOuts = watchOuts.joinToString("\n"),
            status = CandidateStatus.Pending
        )
    }

    private fun clusterBySimilarAmount(transactions: List<TransactionSummary>): List<List<TransactionSummary>> {
        val clusters = mutableListOf<MutableList<TransactionSummary>>()
        for (transaction in transactions.sortedBy { it.amountCents }) {
            val cluster = clusters.firstOrNull { existing ->
                val average = existing.map { it.amountCents }.average().toInt()
                isSimilarAmount(transaction.amountCents, average, transaction.category, transaction.merchantName)
            }
            if (cluster == null) {
                clusters += mutableListOf(transaction)
            } else {
                cluster += transaction
            }
        }
        return clusters.filter { it.size >= 2 }
    }

    private fun classifyCadence(sorted: List<TransactionSummary>, gaps: List<Long>): String? {
        if (gaps.isEmpty()) return null
        val average = gaps.average()
        val daySpread = gaps.maxOrNull()!! - gaps.minOrNull()!!
        val stableDayOfMonth = sorted.map { it.date.dayOfMonth }.let { days -> days.maxOrNull()!! - days.minOrNull()!! <= 3 }
        return when {
            average in 6.0..8.0 && daySpread <= 2 -> "Weekly"
            average in 13.0..16.0 && daySpread <= 3 -> "Biweekly"
            average in 26.0..30.0 && daySpread <= 3 && !stableDayOfMonth -> "Every 4 weeks"
            average in 25.0..35.0 && daySpread <= 7 -> "Monthly"
            average in 80.0..100.0 && daySpread <= 14 -> "Quarterly"
            average in 170.0..195.0 && daySpread <= 21 -> "Semiannual"
            average in 330.0..395.0 && daySpread <= 35 -> "Annual"
            sorted.size >= 3 -> "Irregular but repeated"
            else -> null
        }
    }

    private fun predictedGapDays(cadence: String?, averageGap: Double): Long =
        when (cadence) {
            "Weekly" -> 7
            "Biweekly" -> 14
            "Every 4 weeks" -> 28
            "Monthly" -> 30
            "Quarterly" -> 91
            "Semiannual" -> 182
            "Annual" -> 365
            else -> averageGap.toLong().coerceAtLeast(7)
        }

    private fun predictionWindow(
        predictedDate: java.time.LocalDate,
        cadence: String?,
        candidateType: String,
        stableAmount: Boolean
    ): Pair<java.time.LocalDate, java.time.LocalDate> {
        val spread = when {
            candidateType == "bill" || !stableAmount -> 5L
            cadence == "Annual" -> 7L
            cadence == "Quarterly" || cadence == "Semiannual" -> 4L
            cadence == "Monthly" -> 2L
            else -> 1L
        }
        return predictedDate.minusDays(spread) to predictedDate.plusDays(spread)
    }

    private fun watchOuts(
        sorted: List<TransactionSummary>,
        predictedDate: java.time.LocalDate,
        window: Pair<java.time.LocalDate, java.time.LocalDate>,
        averageAmount: Int
    ): List<String> {
        val watchOuts = mutableListOf<String>()
        val latest = sorted.last()
        val previous = sorted.dropLast(1).lastOrNull()
        if (previous != null && latest.amountCents > (previous.amountCents * 1.08).toInt() && latest.amountCents - previous.amountCents >= 100) {
            watchOuts += "Price increased"
        }
        val gaps = sorted.zipWithNext { first, second -> second.date.toEpochDay() - first.date.toEpochDay() }
        val expectedGap = gaps.dropLast(1).takeIf { it.isNotEmpty() }?.average()
        val latestGap = gaps.lastOrNull()
        if (expectedGap != null && latestGap != null && latestGap < expectedGap - 3) {
            watchOuts += "Charged earlier than usual"
        }
        val recentDuplicate = sorted.takeLast(2).takeIf { it.size == 2 }?.let {
            java.time.temporal.ChronoUnit.DAYS.between(it.first().date, it.last().date) <= 3 &&
                kotlin.math.abs(it.first().amountCents - it.last().amountCents) <= stableTolerance(averageAmount)
        } == true
        if (recentDuplicate) watchOuts += "Duplicate charge suspected"
        if (java.time.LocalDate.now().isAfter(window.second.plusDays(14))) {
            watchOuts += "Subscription not seen recently"
        }
        val text = sorted.joinToString(" ") { "${it.merchantName} ${it.originalDescription} ${it.category}" }.lowercase()
        if ("trial" in text || "free trial" in text) watchOuts += "Free trial may convert soon"
        if (predictedDate.dayOfWeek == java.time.DayOfWeek.SATURDAY || predictedDate.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
            watchOuts += "May shift for weekend or holiday"
        }
        return watchOuts.distinct()
    }

    private fun confidenceFor(count: Int, cadence: String?, stableAmount: Boolean): Float =
        when {
            count >= 3 && cadence != "Irregular but repeated" && stableAmount -> 0.92f
            count >= 3 && cadence != null -> 0.78f
            count == 2 && cadence == "Monthly" && stableAmount -> 0.64f
            else -> 0.42f
        }

    private fun buildReason(count: Int, cadence: String, stableAmount: Boolean, type: String): String {
        val amountText = if (stableAmount) "stable amount" else "variable amount"
        return "$count recurring outflows with $amountText and $cadence cadence; marked as $type for review."
    }

    private fun classifyCandidateType(transactions: List<TransactionSummary>): String {
        val text = transactions.joinToString(" ") { "${it.merchantName} ${it.category}" }.lowercase()
        val billTerms = listOf("utility", "electric", "water", "gas", "phone", "wireless", "insurance", "internet", "telecom")
        return if (billTerms.any { it in text }) "bill" else "subscription"
    }

    private fun isIncome(transaction: TransactionSummary): Boolean {
        val text = "${transaction.merchantName} ${transaction.originalDescription} ${transaction.category}".lowercase()
        return transaction.amountCents <= 0 || listOf("payroll", "paycheck", "salary", "income", "deposit").any { it in text }
    }

    private fun isSimilarAmount(amount: Int, baseline: Int, category: String, merchantName: String): Boolean {
        val variable = classifyCandidateType(
            listOf(
                TransactionSummary(
                    accountId = "",
                    date = java.time.LocalDate.now(),
                    merchantName = merchantName,
                    amountCents = amount,
                    category = category
                )
            )
        ) == "bill"
        val tolerance = if (variable) maxOf(2_500, (baseline * 0.45).toInt()) else stableTolerance(baseline)
        return kotlin.math.abs(amount - baseline) <= tolerance
    }

    private fun stableTolerance(amount: Int): Int = maxOf(100, (amount * 0.08).toInt())

    private fun amountSpread(transactions: List<TransactionSummary>): Int =
        transactions.maxOf { it.amountCents } - transactions.minOf { it.amountCents }

    private fun normalizeMerchant(value: String): String =
        value
            .trim()
            .lowercase()
            .replace(Regex("[*#][a-z0-9]+"), " ")
            .replace(Regex("\\b(pos|debit|card|purchase|payment|online|inc|llc|co)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}

class RenewalCandidateRepository(private val bankDao: BankDao) {
    val candidates: Flow<List<RenewalCandidate>> = bankDao.observeAllCandidates()

    suspend fun updateCandidate(candidate: RenewalCandidate) = bankDao.upsertCandidate(candidate)

    suspend fun markConfirmed(candidate: RenewalCandidate) =
        bankDao.setCandidateStatus(candidate.candidateId, CandidateStatus.Confirmed)

    suspend fun ignore(candidate: RenewalCandidate) =
        bankDao.setCandidateStatus(candidate.candidateId, CandidateStatus.Ignored)

    suspend fun dismiss(candidate: RenewalCandidate) =
        bankDao.setCandidateStatus(candidate.candidateId, CandidateStatus.Dismissed)

    suspend fun ignoreAll(candidates: List<RenewalCandidate>) {
        val ids = candidates.map { it.candidateId }
        if (ids.isNotEmpty()) bankDao.setCandidateStatuses(ids, CandidateStatus.Ignored)
    }

    suspend fun delete(candidate: RenewalCandidate) = bankDao.deleteCandidate(candidate)

    fun toRenewalItem(candidate: RenewalCandidate): RenewalItem =
        RenewalItem(
            title = candidate.merchantName,
            category = if (candidate.candidateType == "bill") "Detected bill" else "Detected subscription",
            dueDate = candidate.nextChargeDate,
            notes = listOf(
                candidate.notes,
                "Detected from connected account summary. Average amount: ${formatCents(candidate.amountCents)}. Cadence: ${candidate.cadence}. Expected window: ${candidate.nextChargeWindowStart} to ${candidate.nextChargeWindowEnd}. ${candidate.reasonDetected}",
                candidate.watchOuts.takeIf { it.isNotBlank() }?.let { "Watch-outs:\n$it" }.orEmpty()
            ).filter { it.isNotBlank() }.joinToString("\n\n"),
            renewWindowDays = 70,
            attentionWindowDays = candidate.reminderDays,
            notify = true
        )
}

private fun ConnectedAccountSummary.toEntity(
    lastSyncedAtMillis: Long?,
    status: BankConnectionStatus
): ConnectedAccount =
    ConnectedAccount(
        accountId = accountId,
        institutionName = institutionName,
        accountName = accountName,
        accountMask = accountMask,
        accountType = accountType,
        status = status,
        lastSyncedAtMillis = lastSyncedAtMillis
    )

fun formatCents(amountCents: Int): String {
    val dollars = amountCents / 100
    val cents = amountCents % 100
    return "\$${dollars}.${cents.toString().padStart(2, '0')}"
}
