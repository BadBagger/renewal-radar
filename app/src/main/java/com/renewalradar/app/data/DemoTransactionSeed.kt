package com.renewalradar.app.data

import java.time.LocalDate

object DemoTransactionSeed {
    fun transactions(today: LocalDate = LocalDate.now()): List<TransactionSummary> {
        val accountId = BankConnectionRepository.DEMO_ACCOUNT_ID
        return buildList {
            monthly(accountId, today, "Netflix", 1599, "Entertainment")
            monthly(accountId, today, "Spotify", 1199, "Entertainment")
            monthly(accountId, today, "Adobe", 3299, "General services")
            yearly(accountId, today, "Amazon Prime", 13900, "General merchandise")
            monthly(accountId, today, "Gym Membership", 2999, "Fitness")
            monthly(accountId, today, "Phone Bill", 8499, "Phone", deltas = listOf(-240, 180, 520, -120))
            monthly(accountId, today, "Electric Utility", 15640, "Utilities", deltas = listOf(-2200, 1400, 3100, -800))
            monthly(accountId, today, "Cloud Backup", 999, "Subscription", deltas = listOf(0, 0, 0, 100))
            duplicate(accountId, today, "Video Stream Plus", 1499, "Entertainment")
            retailNoise(accountId, today)
        }
    }

    private fun MutableList<TransactionSummary>.monthly(
        accountId: String,
        today: LocalDate,
        merchant: String,
        amountCents: Int,
        category: String,
        deltas: List<Int> = listOf(0, 0, 0, 0)
    ) {
        deltas.forEachIndexed { index, delta ->
            val monthsAgo = deltas.size - index
            add(
                tx(
                    id = "demo-${merchant.key()}-$index",
                    accountId = accountId,
                    date = today.minusMonths(monthsAgo.toLong()).withDayOfMonth(14),
                    merchant = merchant,
                    amountCents = amountCents + delta,
                    category = category
                )
            )
        }
    }

    private fun MutableList<TransactionSummary>.yearly(
        accountId: String,
        today: LocalDate,
        merchant: String,
        amountCents: Int,
        category: String
    ) {
        listOf(2L, 1L).forEachIndexed { index, yearsAgo ->
            add(
                tx(
                    id = "demo-${merchant.key()}-annual-$index",
                    accountId = accountId,
                    date = today.minusYears(yearsAgo).withMonth(8).withDayOfMonth(3),
                    merchant = merchant,
                    amountCents = amountCents,
                    category = category
                )
            )
        }
    }

    private fun MutableList<TransactionSummary>.duplicate(
        accountId: String,
        today: LocalDate,
        merchant: String,
        amountCents: Int,
        category: String
    ) {
        listOf(today.minusMonths(3).withDayOfMonth(9), today.minusMonths(2).withDayOfMonth(9), today.minusMonths(1).withDayOfMonth(9), today.minusMonths(1).withDayOfMonth(11))
            .forEachIndexed { index, date ->
                add(
                    tx(
                        id = "demo-${merchant.key()}-dup-$index",
                        accountId = accountId,
                        date = date,
                        merchant = merchant,
                        amountCents = amountCents,
                        category = category
                    )
                )
            }
    }

    private fun MutableList<TransactionSummary>.retailNoise(accountId: String, today: LocalDate) {
        val noise = listOf(
            "Amazon Marketplace" to 4372,
            "Amazon Marketplace" to 895,
            "Target" to 6421,
            "Grocery Store" to 8120,
            "Coffee Shop" to 525
        )
        noise.forEachIndexed { index, (merchant, amount) ->
            add(
                tx(
                    id = "demo-noise-$index",
                    accountId = accountId,
                    date = today.minusDays((index + 2L) * 4L),
                    merchant = merchant,
                    amountCents = amount,
                    category = "General merchandise"
                )
            )
        }
        add(
            tx(
                id = "demo-payroll",
                accountId = accountId,
                date = today.minusDays(14),
                merchant = "Payroll Deposit",
                amountCents = -85000,
                category = "Income"
            )
        )
    }

    private fun tx(
        id: String,
        accountId: String,
        date: LocalDate,
        merchant: String,
        amountCents: Int,
        category: String
    ): TransactionSummary =
        TransactionSummary(
            transactionId = id,
            accountId = accountId,
            date = date,
            authorizedDate = date.minusDays(1),
            merchantName = merchant,
            originalDescription = merchant,
            amountCents = amountCents,
            category = category,
            paymentChannel = "online",
            accountNickname = "Demo Bank ****0000"
        )

    private fun String.key(): String = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
}
