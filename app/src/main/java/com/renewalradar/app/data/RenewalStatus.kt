package com.renewalradar.app.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class RenewalStatus(val label: String) {
    Safe("Safe"),
    RenewSoon("Renew Soon"),
    NeedsAttention("Needs Attention"),
    Overdue("Overdue")
}

fun calculateRenewalStatus(
    dueDate: LocalDate,
    today: LocalDate = LocalDate.now(),
    renewWindowDays: Int = 70,
    attentionWindowDays: Int = 14
): RenewalStatus {
    val daysUntilDue = ChronoUnit.DAYS.between(today, dueDate)

    return when {
        daysUntilDue < 0 -> RenewalStatus.Overdue
        daysUntilDue <= attentionWindowDays -> RenewalStatus.NeedsAttention
        daysUntilDue <= renewWindowDays -> RenewalStatus.RenewSoon
        else -> RenewalStatus.Safe
    }
}
