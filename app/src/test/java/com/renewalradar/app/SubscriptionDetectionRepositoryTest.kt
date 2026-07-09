package com.renewalradar.app

import com.renewalradar.app.data.SubscriptionDetectionRepository
import com.renewalradar.app.data.TransactionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SubscriptionDetectionRepositoryTest {
    private val detector = SubscriptionDetectionRepository()

    @Test
    fun `detects monthly recurring merchant`() {
        val transactions = listOf(
            TransactionSummary(accountId = "acct-1", merchantName = "Streaming Service", amountCents = 1599, date = LocalDate.of(2026, 1, 3)),
            TransactionSummary(accountId = "acct-1", merchantName = "Streaming Service", amountCents = 1599, date = LocalDate.of(2026, 2, 2)),
            TransactionSummary(accountId = "acct-1", merchantName = "Streaming Service", amountCents = 1599, date = LocalDate.of(2026, 3, 4))
        )

        val candidates = detector.detectRecurringCandidates(transactions)

        assertEquals(1, candidates.size)
        assertEquals("Streaming Service", candidates.first().merchantName)
        assertEquals("Monthly", candidates.first().cadence)
        assertEquals("subscription", candidates.first().candidateType)
        assertEquals(3, candidates.first().transactionsUsed)
        assertEquals(LocalDate.of(2026, 4, 3), candidates.first().nextChargeDate)
        assertTrue(candidates.first().nextChargeWindowStart <= candidates.first().nextChargeDate)
        assertTrue(candidates.first().nextChargeWindowEnd >= candidates.first().nextChargeDate)
    }

    @Test
    fun `ignores one-off transactions`() {
        val transactions = listOf(
            TransactionSummary(accountId = "acct-1", merchantName = "Coffee Shop", amountCents = 525, date = LocalDate.of(2026, 1, 3)),
            TransactionSummary(accountId = "acct-1", merchantName = "Grocery Store", amountCents = 7200, date = LocalDate.of(2026, 1, 5))
        )

        val candidates = detector.detectRecurringCandidates(transactions)

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `marks utility cadence as bill with variable amount`() {
        val transactions = listOf(
            TransactionSummary(accountId = "acct-1", merchantName = "Electric Utility", amountCents = 14220, date = LocalDate.of(2026, 1, 12), category = "UTILITIES"),
            TransactionSummary(accountId = "acct-1", merchantName = "Electric Utility", amountCents = 16840, date = LocalDate.of(2026, 2, 11), category = "UTILITIES"),
            TransactionSummary(accountId = "acct-1", merchantName = "Electric Utility", amountCents = 15110, date = LocalDate.of(2026, 3, 13), category = "UTILITIES")
        )

        val candidates = detector.detectRecurringCandidates(transactions)

        assertEquals(1, candidates.size)
        assertEquals("bill", candidates.first().candidateType)
        assertEquals("Monthly", candidates.first().cadence)
        assertTrue(candidates.first().amountVarianceCents > 0)
        assertTrue(candidates.first().nextChargeWindowEnd.toEpochDay() - candidates.first().nextChargeWindowStart.toEpochDay() >= 10)
    }

    @Test
    fun `flags price increase watch out`() {
        val transactions = listOf(
            TransactionSummary(accountId = "acct-1", merchantName = "Streaming Service", amountCents = 999, date = LocalDate.of(2026, 1, 14)),
            TransactionSummary(accountId = "acct-1", merchantName = "Streaming Service", amountCents = 999, date = LocalDate.of(2026, 2, 14)),
            TransactionSummary(accountId = "acct-1", merchantName = "Streaming Service", amountCents = 1099, date = LocalDate.of(2026, 3, 14))
        )

        val candidates = detector.detectRecurringCandidates(transactions)

        assertEquals(1, candidates.size)
        assertTrue(candidates.first().watchOuts.contains("Price increased"))
    }

    @Test
    fun `excludes paycheck income from candidates`() {
        val transactions = listOf(
            TransactionSummary(accountId = "acct-1", merchantName = "Payroll Deposit", amountCents = -85000, date = LocalDate.of(2026, 1, 12), category = "INCOME"),
            TransactionSummary(accountId = "acct-1", merchantName = "Payroll Deposit", amountCents = -85000, date = LocalDate.of(2026, 1, 26), category = "INCOME")
        )

        val candidates = detector.detectRecurringCandidates(transactions)

        assertTrue(candidates.isEmpty())
    }
}
