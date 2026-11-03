package com.github.kr328.clash.service.data.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.github.kr328.clash.service.model.XboardProfile

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE imported ADD COLUMN ageSecretKey TEXT")
        database.execSQL("ALTER TABLE pending ADD COLUMN ageSecretKey TEXT")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Only this account slot inherited the old panel default. Preserve other intervals
        // (including zero) and leave independently imported subscriptions untouched.
        val args = arrayOf<Any>(3_600_000L, XboardProfile.id.toString(), 86_400_000L)
        database.execSQL("UPDATE imported SET interval = ? WHERE uuid = ? AND interval = ?", args)
        database.execSQL("UPDATE pending SET interval = ? WHERE uuid = ? AND interval = ?", args)
    }
}

val MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
)

val LEGACY_MIGRATION = ::migrationFromLegacy
