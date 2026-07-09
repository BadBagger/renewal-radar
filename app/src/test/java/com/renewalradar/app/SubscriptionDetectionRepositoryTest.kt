package com.renewalradar.app

import com.renewalradar.app.data.SubscriptionDetectionRepository
import com.renewalradar.app.data.CsvTransactionParser
import com.renewalradar.app.data.DemoTransactionSeed
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

    @Test
    fun `demo seed detects expected staged examples`() {
        val candidates = detector.detectRecurringCandidates(DemoTransactionSeed.transactions(LocalDate.of(2026, 7, 9)))

        val names = candidates.map { it.merchantName }.toSet()
        assertTrue("Netflix" in names)
        assertTrue("Spotify" in names)
        assertTrue("Adobe" in names)
        assertTrue("Amazon Prime" in names)
        assertTrue("Gym Membership" in names)
        assertTrue("Phone Bill" in names)
        assertTrue("Electric Utility" in names)
        assertTrue(candidates.any { it.watchOuts.contains("Duplicate charge suspected") })
        assertTrue(candidates.any { it.watchOuts.contains("Price increased") })
        assertTrue(candidates.none { it.merchantName == "Payroll Deposit" })
    }

    @Test
    fun `csv import parses common bank export headers`() {
        val csv = """
            Date,Description,Amount,Category,Account
            2026-01-14,Netflix,15.99,Entertainment,Visa 1234
            2026-02-14,Netflix,15.99,Entertainment,Visa 1234
            2026-03-14,Netflix,15.99,Entertainment,Visa 1234
        """.trimIndent()

        val transactions = CsvTransactionParser.parse(csv)
        val candidates = detector.detectRecurringCandidates(transactions)

        assertEquals(3, transactions.size)
        assertEquals(1, candidates.size)
        assertEquals("Netflix", candidates.first().merchantName)
    }
}
