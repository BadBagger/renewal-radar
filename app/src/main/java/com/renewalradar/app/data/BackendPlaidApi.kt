package com.renewalradar.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class BackendPlaidApi(
    private val config: BackendApiConfig
) : PlaidBackendApi {

    override suspend fun createLinkToken(): PlaidLinkToken = withContext(Dispatchers.IO) {
        val json = request("POST", "/api/plaid/create-link-token", JSONObject())
        PlaidLinkToken(
            token = json.getString("link_token"),
            expirationMillis = json.optString("expiration")
                .takeIf { it.isNotBlank() }
                ?.let { Instant.parse(it).toEpochMilli() }
                ?: (System.currentTimeMillis() + 30 * 60 * 1000)
        )
    }

    override suspend fun exchangePublicToken(
        publicToken: PlaidPublicToken,
        metadata: PlaidLinkMetadata?
    ): BankConnectResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("public_token", publicToken.value)
            .put("institution", metadata?.institution?.toJson() ?: JSONObject.NULL)
            .put("accounts", JSONArray().also { array ->
                metadata?.accounts.orEmpty().forEach { account -> array.put(account.toJson()) }
            })
        val json = request("POST", "/api/plaid/exchange-public-token", body)
        BankConnectResult(accounts = json.optJSONArray("accounts").toAccountSummaries())
    }

    override suspend fun exchangePublicToken(publicToken: PlaidPublicToken): List<ConnectedAccountSummary> =
        exchangePublicToken(publicToken, null).accounts

    override suspend fun syncRecurringTransactions(): BankSyncSummary = withContext(Dispatchers.IO) {
        val json = request("POST", "/api/plaid/sync-transactions", JSONObject())
        BankSyncSummary(
            accounts = emptyList(),
            candidates = emptyList(),
            syncedAtMillis = json.optString("syncedAt")
                .takeIf { it.isNotBlank() }
                ?.let { Instant.parse(it).toEpochMilli() }
                ?: System.currentTimeMillis()
        )
    }

    suspend fun getCandidates(): List<RenewalCandidate> = withContext(Dispatchers.IO) {
        request("GET", "/api/renewals/candidates", null)
            .optJSONArray("candidates")
            .toCandidates()
    }

    override suspend fun disconnectAccount(accountId: String) = withContext(Dispatchers.IO) {
        request("POST", "/api/plaid/disconnect", JSONObject().put("accountId", accountId))
        Unit
    }

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val baseUrl = config.baseUrl.trimEnd('/')
        val connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Renewal-User-Id", config.userId)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }

        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) {
                throw IOException("Backend request failed ${connection.responseCode}: $responseText")
            }
            return if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }
}

data class BackendApiConfig(
    val baseUrl: String,
    val userId: String = "local-user",
    val allowLocalHttp: Boolean = true
) {
    init {
        val normalized = baseUrl.lowercase()
        val isLocal = normalized.startsWith("http://10.0.2.2") ||
            normalized.startsWith("http://localhost") ||
            normalized.startsWith("http://127.0.0.1")
        require(normalized.startsWith("https://") || (allowLocalHttp && isLocal)) {
            "Bank backend must use HTTPS except local emulator development."
        }
    }
}

fun PlaidInstitutionMetadata.toJson(): JSONObject =
    JSONObject()
        .put("institution_id", id)
        .put("name", name)

fun PlaidAccountMetadata.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("name", name)
        .put("mask", mask)
        .put("type", type)
        .put("subtype", subtype)

private fun JSONArray?.toAccountSummaries(): List<ConnectedAccountSummary> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = getJSONObject(index)
            add(
                ConnectedAccountSummary(
                    accountId = item.getString("id"),
                    institutionName = item.optString("institutionName", "Connected institution"),
                    accountName = item.optString("name", "Account"),
                    accountMask = item.optString("mask", ""),
                    accountType = item.optString("subtype", item.optString("type", "account"))
                )
            )
        }
    }
}

private fun JSONArray?.toCandidates(): List<RenewalCandidate> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val item = getJSONObject(index)
            add(
                RenewalCandidate(
                    candidateId = item.getString("id"),
                    merchantName = item.getString("merchantName"),
                    amountCents = item.optInt("averageAmountCents", item.getInt("amountCents")),
                    averageAmountCents = item.optInt("averageAmountCents", item.optInt("amountCents")),
                    cadence = item.optString("cadence", "Monthly"),
                    nextChargeDate = java.time.LocalDate.parse(
                        item.optString("predictedNextChargeDate", item.getString("nextChargeDate"))
                    ),
                    lastChargeDate = java.time.LocalDate.parse(
                        item.optString(
                            "lastChargeDate",
                            item.optString("predictedNextChargeDate", item.getString("nextChargeDate"))
                        )
                    ),
                    sourceAccountId = item.optString("accountId", ""),
                    confidence = item.optDouble("confidenceScore", item.optDouble("confidence", 0.8)).toFloat(),
                    transactionsUsed = item.optJSONArray("transactionsUsed")?.length()
                        ?: item.optInt("transactionsUsed", 0),
                    accountNickname = item.optString("accountNickname", ""),
                    category = item.optString("category", ""),
                    reasonDetected = item.optString("reasonDetected", ""),
                    candidateType = item.optString("candidateType", "subscription"),
                    matchingTransactions = item.optJSONArray("matchingTransactions").toTransactionPreviewText(),
                    reminderDays = item.optInt("reminderDays", 14),
                    notes = item.optString("notes", ""),
                    amountVarianceCents = item.optInt("amountVarianceCents", item.optInt("amountVariance", 0)),
                    nextChargeWindowStart = java.time.LocalDate.parse(
                        item.optString(
                            "nextChargeWindowStart",
                            item.optString("predictedNextChargeDate", item.getString("nextChargeDate"))
                        )
                    ),
                    nextChargeWindowEnd = java.time.LocalDate.parse(
                        item.optString(
                            "nextChargeWindowEnd",
                            item.optString("predictedNextChargeDate", item.getString("nextChargeDate"))
                        )
                    ),
                    userEditedNextDate = item.optBoolean("userEditedNextDate", false),
                    inactive = item.optBoolean("inactive", false),
                    watchOuts = item.optJSONArray("watchOuts").toStringLines(),
                    status = when (item.optString("status", "pending").lowercase()) {
                        "confirmed" -> CandidateStatus.Confirmed
                        "ignored" -> CandidateStatus.Ignored
                        "dismissed" -> CandidateStatus.Dismissed
                        else -> CandidateStatus.Pending
                    }
                )
            )
        }
    }
}

private fun JSONArray?.toStringLines(): String {
    if (this == null) return ""
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }.joinToString("\n")
}

private fun JSONArray?.toTransactionPreviewText(): String {
    if (this == null) return ""
    return buildList {
        for (index in 0 until length().coerceAtMost(3)) {
            val item = optJSONObject(index) ?: continue
            val amount = formatCents(item.optInt("amountCents", 0))
            val date = item.optString("date", "")
            val merchant = item.optString("merchantName", "Charge")
            add("$date $amount $merchant".trim())
        }
    }.joinToString("\n")
}
