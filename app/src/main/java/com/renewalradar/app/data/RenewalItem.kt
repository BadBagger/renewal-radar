package com.renewalradar.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(tableName = "renewal_items")
data class RenewalItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: String,
    val dueDate: LocalDate,
    val notes: String = "",
    val renewWindowDays: Int = 70,
    val attentionWindowDays: Int = 14,
    val notify: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun status(today: LocalDate = LocalDate.now()): RenewalStatus =
        calculateRenewalStatus(dueDate, today, renewWindowDays, attentionWindowDays)
}
