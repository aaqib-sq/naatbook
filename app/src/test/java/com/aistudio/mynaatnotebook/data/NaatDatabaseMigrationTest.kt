package com.aistudio.mynaatnotebook.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NaatDatabaseMigrationTest {

    private lateinit var context: Context
    private var database: NaatDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun `migration 1 through 9 preserves rows normalizes categories and applies column additions`() {
        createVersionOneDatabase()

        database = Room.databaseBuilder(context, NaatDatabase::class.java, TEST_DATABASE)
            .addMigrations(
                NaatDatabase.MIGRATION_1_2,
                NaatDatabase.MIGRATION_2_3,
                NaatDatabase.MIGRATION_3_4,
                NaatDatabase.MIGRATION_4_5,
                NaatDatabase.MIGRATION_5_6,
                NaatDatabase.MIGRATION_6_7,
                NaatDatabase.MIGRATION_7_8,
                NaatDatabase.MIGRATION_8_9
            )
            .allowMainThreadQueries()
            .build()

        val migrated = linkedMapOf<String, String>()
        database!!.openHelper.writableDatabase
            .query("SELECT title, category FROM naats ORDER BY id")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    migrated[cursor.getString(0)] = cursor.getString(1)
                }
            }

        assertEquals(0, database!!.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM naats WHERE secondaryAudioType != 'none' OR secondaryAudioPath IS NOT NULL")
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
        assertEquals(0, database!!.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM naats WHERE updatedAt != createdAt")
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
        // v5 display-name columns arrive empty for every pre-existing row.
        assertEquals(0, database!!.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM naats WHERE audioDisplayName IS NOT NULL OR secondaryAudioDisplayName IS NOT NULL")
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
        // v7 font-override column arrives null for every pre-existing row.
        assertEquals(0, database!!.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM naats WHERE readerFontOverride IS NOT NULL")
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
        // v8 title-font-override column arrives null for every pre-existing
        // row; the backfill only copies non-null reader overrides.
        assertEquals(0, database!!.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM naats WHERE titleFontOverride IS NOT NULL")
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })

        assertEquals(
            linkedMapOf(
                "Legacy mixed" to NaatCategories.NAAT,
                "Legacy salam" to NaatCategories.SALAM,
                "Own poetry" to NaatCategories.MY_KALAM,
                "Audio entry" to NaatCategories.MY_KALAM,
                "Already current" to NaatCategories.HAMD,
                "Unknown" to NaatCategories.OTHERS
            ),
            migrated
        )

        // v9: the hot-path indexes exist once the full chain has run.
        val indexNames = mutableListOf<String>()
        database!!.openHelper.writableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'naats'")
            .use { cursor ->
                while (cursor.moveToNext()) indexNames.add(cursor.getString(0))
            }
        assertTrue(indexNames.contains("index_naats_createdAt"))
        assertTrue(indexNames.contains("index_naats_category"))
        assertTrue(indexNames.contains("index_naats_lastOpenedAt"))
    }

    @Test
    fun `migration 5 to 6 adds nullable lastOpenedAt preserving rows`() {
        createVersionFiveDatabase()

        database = Room.databaseBuilder(context, NaatDatabase::class.java, TEST_DATABASE)
            // The annotation now targets v9, so Room needs the full 5->9 path;
            // the assertions below still verify exactly the 5->6 step.
            .addMigrations(NaatDatabase.MIGRATION_5_6, NaatDatabase.MIGRATION_6_7, NaatDatabase.MIGRATION_7_8, NaatDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()

        val columns = mutableListOf<String>()
        database!!.openHelper.writableDatabase
            .query("PRAGMA table_info(naats)")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        assertTrue(columns.contains("lastOpenedAt"))

        val rows = linkedMapOf<String, String?>()
        database!!.openHelper.writableDatabase
            .query("SELECT title, updatedAt, lastOpenedAt FROM naats ORDER BY id")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    rows[cursor.getString(0)] =
                        "${cursor.getLong(1)}:${if (cursor.isNull(2)) "null" else cursor.getLong(2)}"
                }
            }
        // Rows and their edit times survive; the new column arrives NULL.
        assertEquals(
            linkedMapOf("First" to "10:null", "Second" to "20:null"),
            rows
        )
    }

    @Test
    fun `migration 6 to 7 adds readerFontOverride with null default and keeps rows`() {
        createVersionSixDatabase()

        database = Room.databaseBuilder(context, NaatDatabase::class.java, TEST_DATABASE)
            // The annotation now targets v9, so Room needs the full 6->9 path;
            // the assertions below still verify exactly the 6->7 step.
            .addMigrations(NaatDatabase.MIGRATION_6_7, NaatDatabase.MIGRATION_7_8, NaatDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()

        val columns = mutableListOf<String>()
        database!!.openHelper.writableDatabase
            .query("PRAGMA table_info(naats)")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        assertTrue(columns.contains("readerFontOverride"))

        // v7 column arrives null for every pre-existing row; other columns intact.
        assertEquals(0, database!!.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM naats WHERE readerFontOverride IS NOT NULL")
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
        database!!.openHelper.writableDatabase
            .query("SELECT title, category, isFavorite FROM naats WHERE title = 'Font row'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Font row", cursor.getString(0))
                assertEquals("Naat", cursor.getString(1))
                assertEquals(0, cursor.getInt(2))
            }

        // A set override survives the migrated schema.
        database!!.openHelper.writableDatabase
            .execSQL("UPDATE naats SET readerFontOverride = 'nastaliq' WHERE title = 'Font row'")
        assertEquals("nastaliq", database!!.openHelper.writableDatabase
            .query("SELECT readerFontOverride FROM naats WHERE title = 'Font row'")
            .use { cursor -> cursor.moveToFirst(); cursor.getString(0) })
    }


    @Test
    fun `migration 7 to 8 adds titleFontOverride with backfill and keeps rows`() {
        createVersionSevenDatabase()

        database = Room.databaseBuilder(context, NaatDatabase::class.java, TEST_DATABASE)
            // The annotation now targets v9, so the 8->9 step rides along;
            // the assertions below still verify exactly the 7->8 step.
            .addMigrations(NaatDatabase.MIGRATION_7_8, NaatDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()

        val columns = mutableListOf<String>()
        database!!.openHelper.writableDatabase
            .query("PRAGMA table_info(naats)")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        assertTrue(columns.contains("titleFontOverride"))

        // Backfill: a set reader override (which visibly drove the title
        // under v7) carries into the new title column; a null stays null.
        assertEquals("serif", database!!.openHelper.writableDatabase
            .query("SELECT titleFontOverride FROM naats WHERE title = 'Split row'")
            .use { cursor -> cursor.moveToFirst(); cursor.getString(0) })
        assertEquals(1, database!!.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM naats WHERE titleFontOverride IS NULL")
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })

        // Rows and the unrelated columns are preserved.
        assertEquals(2, database!!.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM naats")
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
        assertEquals("Naat", database!!.openHelper.writableDatabase
            .query("SELECT category FROM naats WHERE title = 'Split row'")
            .use { cursor -> cursor.moveToFirst(); cursor.getString(0) })
        assertEquals("serif", database!!.openHelper.writableDatabase
            .query("SELECT readerFontOverride FROM naats WHERE title = 'Split row'")
            .use { cursor -> cursor.moveToFirst(); cursor.getString(0) })
    }


    @Test
    fun `migration 8 to 9 creates hot-path indexes and keeps rows`() {
        createVersionEightDatabase()

        database = Room.databaseBuilder(context, NaatDatabase::class.java, TEST_DATABASE)
            .addMigrations(NaatDatabase.MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()

        val indexNames = mutableListOf<String>()
        database!!.openHelper.writableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'naats'")
            .use { cursor ->
                while (cursor.moveToNext()) indexNames.add(cursor.getString(0))
            }
        assertTrue(indexNames.contains("index_naats_createdAt"))
        assertTrue(indexNames.contains("index_naats_category"))
        assertTrue(indexNames.contains("index_naats_lastOpenedAt"))
        // The pre-existing updatedAt index survives alongside the new ones.
        assertTrue(indexNames.contains("index_naats_updatedAt"))

        // Rows and their data are untouched by an index-only step.
        assertEquals(2, database!!.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM naats")
            .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
        assertEquals("serif", database!!.openHelper.writableDatabase
            .query("SELECT titleFontOverride FROM naats WHERE title = 'Split row'")
            .use { cursor -> cursor.moveToFirst(); cursor.getString(0) })
    }

    private fun createVersionEightDatabase() {
        val file = context.getDatabasePath(TEST_DATABASE)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // Faithful v8: the v7 schema plus the nullable titleFontOverride
            // column MIGRATION_7_8 adds, with its backfill already applied.
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS naats (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    poet TEXT,
                    category TEXT NOT NULL,
                    lyrics TEXT,
                    audioType TEXT NOT NULL,
                    audioPath TEXT,
                    isFavorite INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    secondaryAudioType TEXT NOT NULL,
                    secondaryAudioPath TEXT,
                    audioDisplayName TEXT,
                    secondaryAudioDisplayName TEXT,
                    lastOpenedAt INTEGER,
                    readerFontOverride TEXT,
                    titleFontOverride TEXT
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_naats_updatedAt ON naats(updatedAt)")
            db.execSQL(
                "INSERT INTO naats " +
                    "(title, poet, category, lyrics, audioType, audioPath, isFavorite, createdAt, " +
                    "updatedAt, secondaryAudioType, lastOpenedAt, readerFontOverride, titleFontOverride) " +
                    "VALUES ('Split row', NULL, 'Naat', 'line', 'none', NULL, 0, 1, 1, 'none', NULL, 'serif', 'serif')"
            )
            db.execSQL(
                "INSERT INTO naats " +
                    "(title, poet, category, lyrics, audioType, audioPath, isFavorite, createdAt, " +
                    "updatedAt, secondaryAudioType, lastOpenedAt, readerFontOverride, titleFontOverride) " +
                    "VALUES ('Plain row', NULL, 'Naat', 'line', 'none', NULL, 0, 2, 2, 'none', NULL, NULL, NULL)"
            )
            db.version = 8
        }
    }

    private fun createVersionSevenDatabase() {
        val file = context.getDatabasePath(TEST_DATABASE)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // Faithful v7: the v6 schema plus the nullable readerFontOverride
            // column that MIGRATION_6_7 adds (TEXT, nullable by design).
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS naats (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    poet TEXT,
                    category TEXT NOT NULL,
                    lyrics TEXT,
                    audioType TEXT NOT NULL,
                    audioPath TEXT,
                    isFavorite INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    secondaryAudioType TEXT NOT NULL,
                    secondaryAudioPath TEXT,
                    audioDisplayName TEXT,
                    secondaryAudioDisplayName TEXT,
                    lastOpenedAt INTEGER,
                    readerFontOverride TEXT
                )
                """.trimIndent()
            )
            // A real v7 database built by the migration chain carries the
            // updatedAt index (MIGRATION_3_4); Room's post-migration schema
            // validation fails without it.
            db.execSQL("CREATE INDEX IF NOT EXISTS index_naats_updatedAt ON naats(updatedAt)")
            // One row with a set reader override (the backfill source), one
            // without (must stay null after migration).
            db.execSQL(
                "INSERT INTO naats " +
                    "(title, poet, category, lyrics, audioType, audioPath, isFavorite, createdAt, " +
                    "updatedAt, secondaryAudioType, lastOpenedAt, readerFontOverride) " +
                    "VALUES ('Split row', NULL, 'Naat', 'line', 'none', NULL, 0, 1, 1, 'none', NULL, 'serif')"
            )
            db.execSQL(
                "INSERT INTO naats " +
                    "(title, poet, category, lyrics, audioType, audioPath, isFavorite, createdAt, " +
                    "updatedAt, secondaryAudioType, lastOpenedAt, readerFontOverride) " +
                    "VALUES ('Plain row', NULL, 'Naat', 'line', 'none', NULL, 0, 2, 2, 'none', NULL, NULL)"
            )
            db.version = 7
        }
    }
    private fun createVersionFiveDatabase() {
        val file = context.getDatabasePath(TEST_DATABASE)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS naats (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    poet TEXT,
                    category TEXT NOT NULL,
                    lyrics TEXT,
                    audioType TEXT NOT NULL,
                    audioPath TEXT,
                    isFavorite INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    secondaryAudioType TEXT NOT NULL,
                    secondaryAudioPath TEXT,
                    audioDisplayName TEXT,
                    secondaryAudioDisplayName TEXT
                )
                """.trimIndent()
            )
            // A real v5 database built by the migration chain carries the
            // updatedAt index (MIGRATION_3_4); Room's post-migration schema
            // validation fails without it.
            db.execSQL("CREATE INDEX IF NOT EXISTS index_naats_updatedAt ON naats(updatedAt)")
            listOf("First" to 10L, "Second" to 20L).forEach { (title, updatedAt) ->
                db.execSQL(
                    "INSERT INTO naats " +
                        "(title, poet, category, lyrics, audioType, audioPath, isFavorite, createdAt, " +
                        "updatedAt, secondaryAudioType) " +
                        "VALUES (?, NULL, 'Naat', NULL, 'none', NULL, 0, ?, ?, 'none')",
                    arrayOf(title, updatedAt, updatedAt)
                )
            }
            db.version = 5
        }
    }

    private fun createVersionSixDatabase() {
        val file = context.getDatabasePath(TEST_DATABASE)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // Faithful v6: the v5 schema plus the nullable lastOpenedAt column
            // that MIGRATION_5_6 adds (INTEGER, nullable by design).
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS naats (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    poet TEXT,
                    category TEXT NOT NULL,
                    lyrics TEXT,
                    audioType TEXT NOT NULL,
                    audioPath TEXT,
                    isFavorite INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    secondaryAudioType TEXT NOT NULL,
                    secondaryAudioPath TEXT,
                    audioDisplayName TEXT,
                    secondaryAudioDisplayName TEXT,
                    lastOpenedAt INTEGER
                )
                """.trimIndent()
            )
            // A real v6 database built by the migration chain carries the
            // updatedAt index (MIGRATION_3_4); Room's post-migration schema
            // validation fails without it.
            db.execSQL("CREATE INDEX IF NOT EXISTS index_naats_updatedAt ON naats(updatedAt)")
            db.execSQL(
                "INSERT INTO naats " +
                    "(title, poet, category, lyrics, audioType, audioPath, isFavorite, createdAt, " +
                    "updatedAt, secondaryAudioType, lastOpenedAt) " +
                    "VALUES ('Font row', NULL, 'Naat', 'line', 'none', NULL, 0, 1, 1, 'none', NULL)"
            )
            db.version = 6
        }
    }

    private fun createVersionOneDatabase() {
        val file = context.getDatabasePath(TEST_DATABASE)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS naats (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    title TEXT NOT NULL,
                    poet TEXT,
                    category TEXT NOT NULL,
                    lyrics TEXT,
                    audioType TEXT NOT NULL,
                    audioPath TEXT,
                    isFavorite INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            listOf(
                "Legacy mixed" to "Hamd-o-Naat",
                "Legacy salam" to "Salam & Qasida",
                "Own poetry" to "My Own Poetry",
                "Audio entry" to "Audio Only",
                "Already current" to "Hamd",
                "Unknown" to "Old custom folder"
            ).forEachIndexed { index, (title, category) ->
                db.execSQL(
                    "INSERT INTO naats " +
                        "(title, poet, category, lyrics, audioType, audioPath, isFavorite, createdAt) " +
                        "VALUES (?, NULL, ?, NULL, 'none', NULL, 0, ?)",
                    arrayOf(title, category, index.toLong())
                )
            }
            db.version = 1
        }
    }

    private companion object {
        const val TEST_DATABASE = "migration-1-2-test.db"
    }
}
