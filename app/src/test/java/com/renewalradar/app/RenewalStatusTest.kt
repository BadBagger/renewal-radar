package com.renewalradar.app

import com.renewalradar.app.data.RenewalStatus
import com.renewalradar.app.data.calculateRenewalStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RenewalStatusTest {
    private val today = LocalDate.of(2026, 7, 4)

    @Test
    fun `safe when due date is more than renew window away`() {
        val status = calculateRenewalStatus(
            dueDate = today.plusDays(71),
            today = today,
            renewWindowDays = 70,
            attentionWindowDays = 14
        )

        assertEquals(RenewalStatus.Safe, status)
    }

    @Test
    fun `renew soon at renew window boundary`() {
        val status = calculateRenewalStatus(
            dueDate = today.plusDays(70),
            today = today,
            renewWindowDays = 70,
            attentionWindowDays = 14
        )

        assertEquals(RenewalStatus.RenewSoon, status)
    }

    @Test
    fun `renew soon outside attention window`() {
        val status = calculateRenewalStatus(
            dueDate = today.plusDays(15),
            today = today,
            renewWindowDays = 70,
            attentionWindowDays = 14
        )

        assertEquals(RenewalStatus.RenewSoon, status)
    }

    @Test
    fun `needs attention at attention window boundary`() {
        val status = calculateRenewalStatus(
            dueDate = today.plusDays(14),
            today = today,
            renewWindowDays = 70,
            attentionWindowDays = 14
        )

        assertEquals(RenewalStatus.NeedsAttention, status)
    }

    @Test
    fun `needs attention on due date`() {
        val status = calculateRenewalStatus(
            dueDate = today,
            today = today,
            renewWindowDays = 70,
            attentionWindowDays = 14
        )

        assertEquals(RenewalStatus.NeedsAttention, status)
    }

    @Test
    fun `overdue after due date`() {
        val status = calculateRenewalStatus(
            dueDate = today.minusDays(1),
            today = today,
            renewWindowDays = 70,
            attentionWindowDays = 14
        )

        assertEquals(RenewalStatus.Overdue, status)
    }
}
