package com.renewalradar.app.data

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object CsvTransactionParser {
    private val dateFormats = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US),
        DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US),
        DateTimeFormatter.ofPattern("M-d-yyyy", Locale.US)
    )

    fun parse(text: String): List<TransactionSummary> {
        val rows = text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotBlank() }
            .map(::parseRow)
            .toList()
        if (rows.size < 2) return emptyList()

        val headers = rows.first().map { it.normalizedHeader() }
        fun rowValue(row: List<String>, vararg names: String): String {
            val index = names
                .map { it.normalizedHeader() }
                .firstNotNullOfOrNull { target -> headers.indexOf(target).takeIf { it >= 0 } }
                ?: return ""
            return row.getOrNull(index).orEmpty().trim()
        }

        return rows.drop(1).mapIndexedNotNull { index, row ->
            val date = parseDate(rowValue(row, "date", "posted date", "transaction date")) ?: return@mapIndexedNotNull null
            val merchant = rowValue(row, "merchantName", "merchant", "name", "description", "originalDescription")
            val amount = parseAmount(rowValue(row, "amount", "debit", "withdrawal", "charge")) ?: return@mapIndexedNotNull null
            val accountId = rowValue(row, "accountId", "account id").ifBlank { "csv-import" }
            TransactionSummary(
                transactionId = rowValue(row, "transactionId", "transaction id", "id").ifBlank { "csv-$index-$accountId-${date}" },
                accountId = accountId,
                date = date,
                authorizedDate = parseDate(rowValue(row, "authorizedDate", "authorized date")),
                merchantName = merchant.ifBlank { rowValue(row, "description", "name").ifBlank { "Imported charge" } },
                originalDescription = rowValue(row, "originalDescription", "description", "name").ifBlank { merchant },
                amountCents = abs(amount),
                currency = rowValue(row, "currency", "iso_currency_code").ifBlank { "USD" },
                category = rowValue(row, "category", "personal finance category"),
                paymentChannel = rowValue(row, "paymentChannel", "payment channel"),
                pending = rowValue(row, "pending").equals("true", ignoreCase = true),
                logoUrl = rowValue(row, "logoUrl", "logo url").ifBlank { null },
                website = rowValue(row, "website").ifBlank { null },
                accountNickname = rowValue(row, "accountNickname", "account nickname", "account").ifBlank { "CSV import" }
            )
        }
    }

    private fun parseRow(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && line.getOrNull(index + 1) == '"' -> {
                    current.append('"')
                    index += 1
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index += 1
        }
        values += current.toString()
        return values
    }

    private fun parseDate(value: String): LocalDate? {
        if (value.isBlank()) return null
        return dateFormats.firstNotNullOfOrNull { formatter ->
            runCatching { LocalDate.parse(value.trim(), formatter) }.getOrNull()
        }
    }

    private fun parseAmount(value: String): Int? {
        if (value.isBlank()) return null
        val negative = value.contains("(") && value.contains(")")
        val clean = value.replace("$", "").replace(",", "").replace("(", "").replace(")", "").trim()
        val parsed = clean.toDoubleOrNull() ?: return null
        val signed = if (negative) -parsed else parsed
        return (signed * 100).roundToInt()
    }

    private fun String.normalizedHeader(): String =
        lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")
}
