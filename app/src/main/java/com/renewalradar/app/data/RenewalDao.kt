package com.renewalradar.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RenewalDao {
    @Query("SELECT * FROM renewal_items ORDER BY dueDate ASC, title ASC")
    fun observeAll(): Flow<List<RenewalItem>>

    @Query("SELECT * FROM renewal_items WHERE id = :id")
    fun observeById(id: Long): Flow<RenewalItem?>

    @Query("SELECT * FROM renewal_items WHERE notify = 1")
    suspend fun getNotifiableItems(): List<RenewalItem>

    @Upsert
    suspend fun upsert(item: RenewalItem)

    @Delete
    suspend fun delete(item: RenewalItem)
}
