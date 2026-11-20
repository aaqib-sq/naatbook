package com.aistudio.mynaatnotebook.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aistudio.mynaatnotebook.data.drive.DriveBackupInfo
import com.aistudio.mynaatnotebook.data.drive.selectPinnedBackupId
import com.aistudio.mynaatnotebook.viewmodel.DriveViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Nine-fixes round: the export skipped-audio count (flaw 3), the restore
 * chooser preview (flaw 9), the pinned safe copy (flaw 5), and the
 * stale-sync reminder rule (flaw 6). The backup-manager half runs on the
 * real manager with an in-memory database; the rotation and reminder
 * rules are pure functions tested directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupSafetyFixesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val temporary = TemporaryFolder()
    private lateinit var database: NaatDatabase
    private lateinit var repository: NaatRepository
    private lateinit var manager: BackupManager

    @Before
    fun setUp() {
        temporary.create()
        database = Room.inMemoryDatabaseBuilder(context, NaatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NaatRepository(database.naatDao())
        manager = BackupManager(
            context, repository, AudioFileLifecycleCoordinator(),
            CustomFontStore(context, SettingsStore(context))
        )
        File(context.filesDir, "audio").deleteRecursively()
    }

    @After
    fun tearDown() {
        database.close()
        File(context.filesDir, "audio").deleteRecursively()
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("backup-import-") || it.name.startsWith("backup-export-") }
            ?.forEach(File::deleteRecursively)
        temporary.delete()
    }

    // Flaw 3: an entry whose audio file is gone from disk is counted, not
    // silently dropped — the export still succeeds, but says so.
    @Test
    fun `export counts entries whose audio file is missing`() = runBlocking {
        val missing = File(context.filesDir, "audio/gone.m4a")
        repository.insert(
            NaatEntity(
                title = "MissingAudio",
                poet = null,
                category = NaatCategories.NAAT,
                lyrics = "line",
                audioType = "recorded",
                audioPath = missing.absolutePath,
                isFavorite = false
            )
        )
        val target = temporary.newFile("missing-audio.zip")

        val result = manager.exportBackupToFile(target).getOrThrow()

        assertEquals(1, result.count)
        assertEquals(1, result.skippedAudio)
    }

    // Flaw 3: when the audio is present, nothing is skipped.
    @Test
    fun `export reports zero skipped audio when files are present`() = runBlocking {
        val dir = File(context.filesDir, "audio").also { it.mkdirs() }
        val source = File(dir, "orig.m4a").also { it.writeBytes("payload".toByteArray()) }
        repository.insert(
            NaatEntity(
                title = "PresentAudio",
                poet = null,
                category = NaatCategories.NAAT,
                lyrics = "line",
                audioType = "recorded",
                audioPath = source.absolutePath,
                isFavorite = false
            )
        )
        val target = temporary.newFile("present-audio.zip")

        val result = manager.exportBackupToFile(target).getOrThrow()

        assertEquals(1, result.count)
        assertEquals(0, result.skippedAudio)
    }

    // Flaw 3: entries-only exports omit audio by design — never counted.
    @Test
    fun `entries-only export never counts skipped audio`() = runBlocking {
        val missing = File(context.filesDir, "audio/gone.m4a")
        repository.insert(
            NaatEntity(
                title = "MissingAudio",
                poet = null,
                category = NaatCategories.NAAT,
                lyrics = "line",
                audioType = "recorded",
                audioPath = missing.absolutePath,
                isFavorite = false
            )
        )
        val target = temporary.newFile("entries-only.zip")

        val result = manager.exportBackupToFile(target, entriesOnly = true).getOrThrow()

        assertEquals(1, result.count)
        assertEquals(0, result.skippedAudio)
    }

    // Flaw 9: the restore chooser preview reads titles from the manifest
    // without importing anything. The backup lists entries newest-first
    // (the library's own order — NaatDao orders by createdAt DESC), so
    // the preview returns them in that order. Explicit createdAt values
    // keep the order deterministic even when the test clock is frozen.
    @Test
    fun `peekEntryTitles returns the first titles in order`() = runBlocking {
        listOf("First Naat" to 1000L, "Second Naat" to 2000L, "Third Naat" to 3000L)
            .forEach { (title, createdAt) ->
                repository.insert(
                    NaatEntity(
                        title = title,
                        poet = null,
                        category = NaatCategories.NAAT,
                        lyrics = "line",
                        audioType = "none",
                        audioPath = null,
                        isFavorite = false,
                        createdAt = createdAt
                    )
                )
            }
        val target = temporary.newFile("peek.zip")
        manager.exportBackupToFile(target).getOrThrow()
        assertEquals(3, repository.allNaats.first().size)

        val titles = manager.peekEntryTitles(Uri.fromFile(target)).getOrThrow()

        assertEquals(listOf("Third Naat", "Second Naat", "First Naat"), titles)
    }

    // Flaw 9: the preview honours the max-titles cap. Newest-first, so
    // the cap takes the two newest titles.
    @Test
    fun `peekEntryTitles honours the max titles cap`() = runBlocking {
        listOf("One" to 1000L, "Two" to 2000L, "Three" to 3000L, "Four" to 4000L)
            .forEach { (title, createdAt) ->
                repository.insert(
                    NaatEntity(
                        title = title,
                        poet = null,
                        category = NaatCategories.NAAT,
                        lyrics = "line",
                        audioType = "none",
                        audioPath = null,
                        isFavorite = false,
                        createdAt = createdAt
                    )
                )
            }
        val target = temporary.newFile("peek-cap.zip")
        manager.exportBackupToFile(target).getOrThrow()

        val titles = manager.peekEntryTitles(Uri.fromFile(target), maxTitles = 2).getOrThrow()

        assertEquals(listOf("Four", "Three"), titles)
    }

    // Flaw 5: rotation never evicts the oldest backup that holds entries.
    @Test
    fun `pinned safe copy is the oldest backup with entries`() {
        val backups = listOf(
            DriveBackupInfo("id5", "naatbook-backup-20260105-120000-n5.zip", 5L),
            DriveBackupInfo("id3", "naatbook-backup-20260103-120000-n3.zip", 3L),
            DriveBackupInfo("id0", "naatbook-backup-20260102-120000-n0.zip", 2L),
            DriveBackupInfo("id2", "naatbook-backup-20260101-120000-n2.zip", 1L)
        )
        assertEquals("id2", selectPinnedBackupId(backups))
    }

    // Flaw 5: an empty backup is never a safe copy.
    @Test
    fun `pinned safe copy skips empty backups`() {
        val backups = listOf(
            DriveBackupInfo("id1", "naatbook-backup-20260102-120000-n1.zip", 2L),
            DriveBackupInfo("id0", "naatbook-backup-20260101-120000-n0.zip", 1L)
        )
        assertEquals("id1", selectPinnedBackupId(backups))
    }

    // Flaw 5: when every backup is empty there is nothing to pin.
    @Test
    fun `no pinned safe copy when all backups are empty`() {
        val backups = listOf(
            DriveBackupInfo("id0a", "naatbook-backup-20260102-120000-n0.zip", 2L),
            DriveBackupInfo("id0b", "naatbook-backup-20260101-120000-n0.zip", 1L)
        )
        assertNull(selectPinnedBackupId(backups))
    }

    // Flaw 5: a pre-count backup name (no -n suffix) is treated as
    // holding entries, so it can be the pinned copy.
    @Test
    fun `pre-count backup name counts as holding entries`() {
        val backups = listOf(
            DriveBackupInfo("idNew", "naatbook-backup-20260102-120000-n4.zip", 2L),
            DriveBackupInfo("idOld", "naatbook-backup-old.zip", 1L)
        )
        assertEquals("idOld", selectPinnedBackupId(backups))
    }

    // Flaw 6: never synced counts as stale.
    @Test
    fun `never synced is stale`() {
        assertTrue(DriveViewModel.isSyncStale(lastSyncAtMs = 0L, nowMs = 1_000_000L))
    }

    // Flaw 6: a sync older than a week counts as stale.
    @Test
    fun `sync older than a week is stale`() {
        val now = 1_000_000_000L
        val eightDaysAgo = now - 8L * 24L * 60L * 60L * 1000L
        assertTrue(DriveViewModel.isSyncStale(eightDaysAgo, now))
    }

    // Flaw 6: a sync within the last week is fresh.
    @Test
    fun `sync within the week is not stale`() {
        val now = 1_000_000_000L
        val sixDaysAgo = now - 6L * 24L * 60L * 60L * 1000L
        assertFalse(DriveViewModel.isSyncStale(sixDaysAgo, now))
        assertFalse(DriveViewModel.isSyncStale(now, now))
    }
}
