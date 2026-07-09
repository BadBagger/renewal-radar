package com.renewalradar.app.data

import android.content.Context
import androidx.room.migration.Migration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [RenewalItem::class, ConnectedAccount::class, RenewalCandidate::class],
    version = 5,
    exportSchema = false
)
@TypeConverters(DateConverters::class)
abstract class RenewalDatabase : RoomDatabase() {
    abstract fun renewalDao(): RenewalDao
    abstract fun bankDao(): BankDao

    companion object {
        @Volatile
        private var instance: RenewalDatabase? = null

        fun get(context: Context): RenewalDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RenewalDatabase::class.java,
                    "renewal-radar.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS connected_accounts (
                        accountId TEXT NOT NULL PRIMARY KEY,
                        institutionName TEXT NOT NULL,
                        accountName TEXT NOT NULL,
                        accountMask TEXT NOT NULL,
                        accountType TEXT NOT NULL,
                        status TEXT NOT NULL,
                        lastSyncedAtMillis INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS renewal_candidates (
                        candidateId TEXT NOT NULL PRIMARY KEY,
                        merchantName TEXT NOT NULL,
                        amountCents INTEGER NOT NULL,
                        cadence TEXT NOT NULL,
                        nextChargeDate INTEGER NOT NULL,
                        sourceAccountId TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        status TEXT NOT NULL,
                        createdAtMillis INTEGER NOT NULL,
                        averageAmountCents INTEGER NOT NULL DEFAULT 0,
                        lastChargeDate INTEGER NOT NULL DEFAULT 0,
                        transactionsUsed INTEGER NOT NULL DEFAULT 0,
                        accountNickname TEXT NOT NULL DEFAULT '',
                        category TEXT NOT NULL DEFAULT '',
                        reasonDetected TEXT NOT NULL DEFAULT '',
                        candidateType TEXT NOT NULL DEFAULT 'subscription',
                        matchingTransactions TEXT NOT NULL DEFAULT '',
                        reminderDays INTEGER NOT NULL DEFAULT 14,
                        notes TEXT NOT NULL DEFAULT '',
                        amountVarianceCents INTEGER NOT NULL DEFAULT 0,
                        nextChargeWindowStart INTEGER NOT NULL DEFAULT 0,
                        nextChargeWindowEnd INTEGER NOT NULL DEFAULT 0,
                        userEditedNextDate INTEGER NOT NULL DEFAULT 0,
                        inactive INTEGER NOT NULL DEFAULT 0,
                        watchOuts TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN averageAmountCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN lastChargeDate INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN transactionsUsed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN accountNickname TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN category TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN reasonDetected TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN candidateType TEXT NOT NULL DEFAULT 'subscription'")
                db.execSQL("UPDATE renewal_candidates SET status = 'Pending' WHERE status = 'New'")
                db.execSQL("UPDATE renewal_candidates SET averageAmountCents = amountCents WHERE averageAmountCents = 0")
                db.execSQL("UPDATE renewal_candidates SET lastChargeDate = nextChargeDate WHERE lastChargeDate = 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN matchingTransactions TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN reminderDays INTEGER NOT NULL DEFAULT 14")
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN amountVarianceCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN nextChargeWindowStart INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN nextChargeWindowEnd INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN userEditedNextDate INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN inactive INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE renewal_candidates ADD COLUMN watchOuts TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE renewal_candidates SET nextChargeWindowStart = nextChargeDate WHERE nextChargeWindowStart = 0")
                db.execSQL("UPDATE renewal_candidates SET nextChargeWindowEnd = nextChargeDate WHERE nextChargeWindowEnd = 0")
            }
        }
    }
}
