package com.renewalradar.app.data

class RenewalRepository(private val dao: RenewalDao) {
    val items = dao.observeAll()

    fun observeItem(id: Long) = dao.observeById(id)

    suspend fun save(item: RenewalItem) = dao.upsert(item.copy(updatedAtMillis = System.currentTimeMillis()))

    suspend fun delete(item: RenewalItem) = dao.delete(item)

    suspend fun notifiableItems(): List<RenewalItem> = dao.getNotifiableItems()
}
