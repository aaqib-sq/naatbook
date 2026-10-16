package com.aistudio.mynaatnotebook.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [NaatEntity::class], version = 7, exportSchema = true)
abstract class NaatDatabase : RoomDatabase() {

    abstract fun naatDao(): NaatDao

    companion object {
        /**
         * v1 -> v2: category/taxonomy restructure. The schema is untouched
         * (category is a plain String column); only the stored values are
         * remapped onto the new taxonomy and the "Audio Only" pseudo-folder
         * disappears. The production builder has no destructive fallback, so
         * every future schema change must also provide an explicit migration.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE naats SET category = 'Naat' WHERE category = 'Hamd-o-Naat'")
                db.execSQL("UPDATE naats SET category = 'Salam' WHERE category = 'Salam & Qasida'")
                db.execSQL("UPDATE naats SET category = 'My Kalam' WHERE category = 'My Own Poetry'")
                db.execSQL("UPDATE naats SET category = 'My Kalam' WHERE category = 'Audio Only'")
                // Safety net: any other stale/unknown label lands in Others.
                db.execSQL(
                    "UPDATE naats SET category = 'Others' WHERE category NOT IN " +
                        "('Naat', 'Hamd', 'Manqabat', 'Salam', 'Qasida', 'Nasheed', 'My Kalam', 'Others')"
                )
            }
        }

        /** v2 -> v3: preserve an independent second audio attachment per entry. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE naats ADD COLUMN secondaryAudioType TEXT NOT NULL DEFAULT 'none'"
                )
                db.execSQL("ALTER TABLE naats ADD COLUMN secondaryAudioPath TEXT")
            }
        }

        /** v3 -> v4: stable modification time for a Room-backed Recent Notebooks list. */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE naats ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                // Existing entries have no historical edit timestamp; creation time is the
                // only truthful initial ordering until their next saved edit.
                db.execSQL("UPDATE naats SET updatedAt = createdAt WHERE updatedAt = 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_naats_updatedAt ON naats(updatedAt)")
            }
        }

        /** v4 -> v5: original display names for linked audio files (editor display only). */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE naats ADD COLUMN audioDisplayName TEXT")
                db.execSQL("ALTER TABLE naats ADD COLUMN secondaryAudioDisplayName TEXT")
            }
        }

        /** v5 -> v6: Reader-open timestamp for a truthful "recently opened" strip.
         *  Nullable by design: pre-existing rows get NULL (COALESCE falls back to
         *  updatedAt), so untouched libraries keep their current order. */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE naats ADD COLUMN lastOpenedAt INTEGER")
            }
        }

        /** v6 -> v7: per-entry reader-font override (null = follow Settings). */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE naats ADD COLUMN readerFontOverride TEXT")
            }
        }
    }
}
