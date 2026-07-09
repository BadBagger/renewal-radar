package com.renewalradar.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BankDao {
    @Query("SELECT * FROM connected_accounts ORDER BY institutionName ASC, accountName ASC")
    fun observeConnectedAccounts(): Flow<List<ConnectedAccount>>

    @Query("SELECT * FROM renewal_candidates WHERE status = 'Pending' ORDER BY nextChargeDate ASC, merchantName ASC")
    fun observeOpenCandidates(): Flow<List<RenewalCandidate>>

    @Query("SELECT * FROM renewal_candidates ORDER BY status ASC, nextChargeDate ASC, merchantName ASC")
    fun observeAllCandidates(): Flow<List<RenewalCandidate>>

    @Query("SELECT * FROM renewal_candidates WHERE candidateId IN (:candidateIds)")
    suspend fun getCandidatesByIds(candidateIds: List<String>): List<RenewalCandidate>

    @Upsert
    suspend fun upsertAccounts(accounts: List<ConnectedAccount>)

    @Query("DELETE FROM connected_accounts WHERE accountId = :accountId")
    suspend fun deleteAccountById(accountId: String)

    @Upsert
    suspend fun upsertCandidates(candidates: List<RenewalCandidate>)

    @Upsert
    suspend fun upsertCandidate(candidate: RenewalCandidate)

    @Query("UPDATE connected_accounts SET status = 'Disconnected' WHERE accountId = :accountId")
    suspend fun markDisconnected(accountId: String)

    @Query("UPDATE connected_accounts SET status = 'Syncing' WHERE status = 'Connected'")
    suspend fun markAllConnectedSyncing()

    @Query("UPDATE connected_accounts SET status = 'Connected', lastSyncedAtMillis = :syncedAtMillis WHERE status = 'Syncing' OR status = 'Connected'")
    suspend fun markAllConnectedSynced(syncedAtMillis: Long)

    @Query("UPDATE connected_accounts SET status = 'SyncFailed' WHERE status = 'Syncing'")
    suspend fun markSyncingFailed()

    @Query("UPDATE renewal_candidates SET status = :status WHERE candidateId = :candidateId")
    suspend fun setCandidateStatus(candidateId: String, status: CandidateStatus)

    @Query("UPDATE renewal_candidates SET status = :status WHERE candidateId IN (:candidateIds)")
    suspend fun setCandidateStatuses(candidateIds: List<String>, status: CandidateStatus)

    @Delete
    suspend fun deleteCandidate(candidate: RenewalCandidate)
}
