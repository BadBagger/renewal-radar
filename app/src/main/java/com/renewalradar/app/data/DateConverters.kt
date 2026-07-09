package com.renewalradar.app.data

import androidx.room.TypeConverter
import java.time.LocalDate

class DateConverters {
    @TypeConverter
    fun fromEpochDay(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun localDateToEpochDay(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun bankConnectionStatusToString(status: BankConnectionStatus?): String? = status?.name

    @TypeConverter
    fun stringToBankConnectionStatus(value: String?): BankConnectionStatus? =
        value?.let(BankConnectionStatus::valueOf)

    @TypeConverter
    fun candidateStatusToString(status: CandidateStatus?): String? = status?.name

    @TypeConverter
    fun stringToCandidateStatus(value: String?): CandidateStatus? =
        when (value) {
            "New" -> CandidateStatus.Pending
            null -> null
            else -> CandidateStatus.valueOf(value)
        }
}
