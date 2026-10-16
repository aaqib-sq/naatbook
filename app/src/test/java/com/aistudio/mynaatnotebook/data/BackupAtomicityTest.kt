package com.aistudio.mynaatnotebook.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupAtomicityTest {
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
        context.cacheDir.listFiles()?.filter { it.name.startsWith("backup-import-") }
            ?.forEach(File::deleteRecursively)
        temporary.delete()
    }

    @Test
    fun `invalid later entry leaves database and audio untouched`() = runBlocking {
        val entries = JSONArray()
            .put(entry("Valid"))
            .put(entry(""))
        val archive = archive(JSONObject().put("formatVersion", 2).put("entries", entries))

        val result = manager.importBackup(Uri.fromFile(archive))

        assertTrue(result.isFailure)
        assertTrue(repository.allNaats.first().isEmpty())
        assertFalse(File(context.filesDir, "audio").exists())
        assertNoImportStaging()
    }

    @Test
    fun `checksum mismatch is rejected before commit`() = runBlocking {
        val declaredDigest = "0".repeat(64)
        val path = "audio/$declaredDigest.m4a"
        val entries = JSONArray().put(entry("Corrupt").put("audioType", "recorded").put("audioPath", path))
        val archive = archive(
            JSONObject().put("formatVersion", 2).put("entries", entries),
            mapOf(path to "not the declared payload".toByteArray())
        )

        val result = manager.importBackup(Uri.fromFile(archive))

        assertTrue(result.isFailure)
        assertTrue(repository.allNaats.first().isEmpty())
        assertFalse(File(context.filesDir, "audio").exists())
        assertNoImportStaging()
    }

    @Test
    fun `version two preserves independent voice and linked attachments`() = runBlocking {
        val voice = "voice".toByteArray()
        val linked = "linked".toByteArray()
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        val voicePath = "audio/${digest(voice)}.m4a"
        val linkedPath = "audio/${digest(linked)}.mp3"
        val dual = entry("Dual")
            .put("audioType", "recorded").put("audioPath", voicePath)
            .put("secondaryAudioType", "local_file").put("secondaryAudioPath", linkedPath)
            .put("updatedAt", 456L)
        val archive = archive(
            JSONObject().put("formatVersion", 2).put("entries", JSONArray().put(dual)),
            mapOf(voicePath to voice, linkedPath to linked)
        )

        assertEquals(1, manager.importBackup(Uri.fromFile(archive)).getOrThrow().imported)
        val restored = repository.allNaats.first().single()
        assertEquals("recorded", restored.audioType)
        assertEquals("local_file", restored.secondaryAudioType)
        assertEquals(456L, restored.updatedAt)
        assertTrue(File(restored.audioPath!!).isFile)
        assertTrue(File(restored.secondaryAudioPath!!).isFile)
    }

    @Test
    fun `version one absolute audio path restores through staged content address`() = runBlocking {
        val payload = "legacy audio".toByteArray()
        val legacyPath = "recordings/legacy.m4a"
        val legacyEntry = entry("Legacy")
            .put("audioType", "recorded")
            .put("audioPath", "/data/user/0/old.app/files/$legacyPath")
        val legacyDocument = JSONArray().put(legacyEntry)
        val archive = temporary.newFile("legacy.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("naatbook_data.json"))
            zip.write(legacyDocument.toString().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(legacyPath))
            zip.write(payload)
            zip.closeEntry()
        }

        val result = manager.importBackup(Uri.fromFile(archive))
        val restored = repository.allNaats.first().single()

        assertEquals(1, result.getOrThrow().imported)
        assertEquals("recorded", restored.audioType)
        assertTrue(restored.audioPath!!.startsWith(File(context.filesDir, "audio").absolutePath))
        assertTrue(File(restored.audioPath!!).isFile)
    }

    @Test
    fun `valid shared audio commits once with all rows`() = runBlocking {
        val payload = "shared audio".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        val path = "audio/$digest.m4a"
        val entries = JSONArray()
            .put(entry("First").put("audioType", "recorded").put("audioPath", path))
            .put(entry("Second").put("audioType", "recorded").put("audioPath", path))
        val archive = archive(
            JSONObject().put("formatVersion", 2).put("entries", entries),
            mapOf(path to payload)
        )

        val result = manager.importBackup(Uri.fromFile(archive))
        val restored = repository.allNaats.first()

        assertEquals(2, result.getOrThrow().imported)
        assertEquals(2, restored.size)
        assertEquals(1, restored.mapNotNull { it.audioPath }.distinct().size)
        assertEquals(1, File(context.filesDir, "audio").listFiles()?.size)
        assertNoImportStaging()
    }

    /**
     * Audit fix (stage 1): two entries with the same duplicate signature
     * inside ONE archive must not both land in MERGE — first occurrence wins
     * and the in-archive duplicate is counted in skipped.
     */
    @Test
    fun `duplicate entries inside one archive commit only once in merge`() = runBlocking {
        val entries = JSONArray()
            .put(entry("Twice"))
            .put(entry("Twice"))
        val archive = archive(JSONObject().put("formatVersion", 2).put("entries", entries))

        val result = manager.importBackup(Uri.fromFile(archive), ImportMode.MERGE).getOrThrow()

        assertEquals(1, result.imported)
        assertEquals(1, result.skipped)
        assertEquals(1, repository.allNaats.first().size)
        assertNoImportStaging()
    }

    /**
     * Audit fix (stage 1): REPLACE now runs the new-row inserts and the
     * old-row deletes inside one Room transaction. The happy-path contract:
     * the old library is fully gone, the new one fully present.
     * (A true mid-delete fault-injection proof isn't possible on the JVM —
     * the guarantee is structural via NaatDao.replaceLibrary's @Transaction.)
     */
    @Test
    fun `replace swaps the whole library and counts removals`() = runBlocking {
        repository.insertAll(
            listOf(
                NaatEntity(
                    title = "Old", poet = null, category = NaatCategories.NAAT,
                    lyrics = null, audioType = "none", audioPath = null, isFavorite = false
                )
            )
        )
        val entries = JSONArray().put(entry("New"))
        val archive = archive(JSONObject().put("formatVersion", 2).put("entries", entries))

        val result = manager.importBackup(Uri.fromFile(archive), ImportMode.REPLACE).getOrThrow()

        assertEquals(1, result.imported)
        assertEquals(0, result.skipped)
        assertEquals(1, result.removedExisting)
        assertEquals(listOf("New"), repository.allNaats.first().map { it.title })
        assertNoImportStaging()
    }

    private fun entry(title: String) = JSONObject()
        .put("title", title)
        .put("poet", JSONObject.NULL)
        .put("category", NaatCategories.NAAT)
        .put("lyrics", JSONObject.NULL)
        .put("audioType", "none")
        .put("audioPath", JSONObject.NULL)
        .put("isFavorite", false)
        .put("createdAt", 1L)

    private fun archive(document: JSONObject, files: Map<String, ByteArray> = emptyMap()): File {
        val output = temporary.newFile("backup-${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry("naatbook_data.json"))
            zip.write(document.toString().toByteArray())
            zip.closeEntry()
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output
    }

    private fun assertNoImportStaging() {
        assertTrue(
            context.cacheDir.listFiles()?.none { it.name.startsWith("backup-import-") } != false
        )
    }
}
