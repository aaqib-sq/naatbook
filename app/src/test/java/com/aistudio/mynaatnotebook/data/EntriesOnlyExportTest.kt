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
import org.junit.Assert.assertNull
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
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Entries-only export: the archive carries the manifest (with audio
 * references) and fonts but no audio payload; import drops the dangling
 * references instead of failing. Missing audio must stay tolerated ONLY
 * for entries-only archives — full backups keep the strict "missing audio
 * payload" failure, and present-but-corrupt audio is rejected in both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EntriesOnlyExportTest {
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

    @Test
    fun `entries-only export omits audio payload but keeps manifest references`() = runBlocking {
        val payload = "recording payload".toByteArray()
        insertEntryWithAudio("EntriesOnly", payload)
        val target = temporary.newFile("entries-only.zip")

        assertEquals(1, manager.exportBackupToFile(target, entriesOnly = true).getOrThrow())

        val names = zipNames(target)
        assertTrue(names.none { it.startsWith("audio/") })
        assertTrue(names.contains("naatbook_data.json"))
        val root = zipJson(target)
        assertTrue(root.optBoolean("entriesOnly", false))
        val manifest = root.getJSONArray("entries").getJSONObject(0)
        val archivePath = manifest.getString("audioPath")
        assertTrue(archivePath.startsWith("audio/"))
        assertEquals(digestOf(payload), archivePath.removePrefix("audio/").substringBefore('.'))
    }

    @Test
    fun `full export still carries audio and no entries-only marker`() = runBlocking {
        val payload = "full payload".toByteArray()
        insertEntryWithAudio("Full", payload)
        val target = temporary.newFile("full.zip")

        assertEquals(1, manager.exportBackupToFile(target).getOrThrow())

        val names = zipNames(target)
        assertTrue(names.contains("audio/${digestOf(payload)}.m4a"))
        assertFalse(zipJson(target).has("entriesOnly"))
    }

    @Test
    fun `entries-only import merges entries with audio dropped`() = runBlocking {
        val path = "audio/${digestOf("gone".toByteArray())}.m4a"
        val archive = archive(entriesOnlyDocument(path))

        val result = manager.importBackup(Uri.fromFile(archive), ImportMode.MERGE)

        assertEquals(1, result.getOrThrow().imported)
        val restored = repository.allNaats.first().single()
        assertEquals("none", restored.audioType)
        assertNull(restored.audioPath)
        assertEquals("none", restored.secondaryAudioType)
        assertNull(restored.secondaryAudioPath)
        // No audio directory may be created by an entries-only import.
        assertFalse(File(context.filesDir, "audio").exists())
    }

    @Test
    fun `entries-only import replaces library with audio dropped`() = runBlocking {
        insertEntryWithAudio("Old", "old audio".toByteArray())
        val path = "audio/${digestOf("gone".toByteArray())}.m4a"
        val archive = archive(entriesOnlyDocument(path))

        val result = manager.importBackup(Uri.fromFile(archive), ImportMode.REPLACE)

        assertEquals(1, result.getOrThrow().imported)
        assertEquals(1, result.getOrThrow().removedExisting)
        val restored = repository.allNaats.first().single()
        assertEquals("Replaced", restored.title)
        assertEquals("none", restored.audioType)
        assertNull(restored.audioPath)
    }

    @Test
    fun `entries-only import still rejects present-but-corrupt audio`() = runBlocking {
        val declaredDigest = "0".repeat(64)
        val path = "audio/$declaredDigest.m4a"
        val archive = archive(
            entriesOnlyDocument(path),
            mapOf(path to "not the declared payload".toByteArray())
        )

        val result = manager.importBackup(Uri.fromFile(archive), ImportMode.MERGE)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("checksum mismatch"))
        assertTrue(repository.allNaats.first().isEmpty())
    }

    @Test
    fun `full backup with missing audio payload still fails`() = runBlocking {
        // No entriesOnly marker: a dangling audio reference is corruption.
        val path = "audio/${digestOf("missing".toByteArray())}.m4a"
        val archive = archive(entriesOnlyDocument(path, marker = false))

        val result = manager.importBackup(Uri.fromFile(archive), ImportMode.MERGE)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Missing audio payload"))
        assertTrue(repository.allNaats.first().isEmpty())
    }

    @Test
    fun `per-entry reader font override round-trips through backup`() = runBlocking {
        repository.insert(
            NaatEntity(
                title = "Fonted",
                poet = null,
                category = NaatCategories.NAAT,
                lyrics = "line",
                audioType = "none",
                audioPath = null,
                isFavorite = false,
                readerFontOverride = "custom:font-1"
            )
        )
        repository.insert(
            NaatEntity(
                title = "Plain",
                poet = null,
                category = NaatCategories.NAAT,
                lyrics = "line",
                audioType = "none",
                audioPath = null,
                isFavorite = false,
                readerFontOverride = null
            )
        )
        val target = temporary.newFile("fonts.zip")

        assertEquals(2, manager.exportBackupToFile(target, entriesOnly = true).getOrThrow())
        // The field travels in the format-v2 export JSON.
        val manifest = zipJson(target).getJSONArray("entries")
        val exported = (0 until manifest.length())
            .map { manifest.getJSONObject(it) }
            .associateBy { it.getString("title") }
        assertEquals("custom:font-1", exported.getValue("Fonted").getString("readerFontOverride"))
        assertTrue(exported.getValue("Plain").isNull("readerFontOverride"))

        val result = manager.importBackup(Uri.fromFile(target), ImportMode.REPLACE)
        assertEquals(2, result.getOrThrow().imported)
        val restored = repository.allNaats.first().associateBy { it.title }
        assertEquals("custom:font-1", restored.getValue("Fonted").readerFontOverride)
        assertNull(restored.getValue("Plain").readerFontOverride)
    }

    @Test
    fun `old backup without the field imports with null override`() = runBlocking {
        // A real v2 entries-only backup carries a content-addressed audio
        // reference ("audio/<sha256>.<ext>") with no payload; a malformed
        // reference would be rejected by the validity guard instead.
        val validV2Reference = "audio/${"a".repeat(64)}.m4a"
        val archive = archive(entriesOnlyDocument(validV2Reference))

        val result = manager.importBackup(Uri.fromFile(archive), ImportMode.MERGE)

        assertEquals(1, result.getOrThrow().imported)
        assertNull(repository.allNaats.first().single().readerFontOverride)
    }

    private suspend fun insertEntryWithAudio(title: String, payload: ByteArray) {
        val dir = File(context.filesDir, "audio").also { it.mkdirs() }
        val source = File(dir, "orig.m4a").also { it.writeBytes(payload) }
        repository.insert(
            NaatEntity(
                title = title,
                poet = null,
                category = NaatCategories.NAAT,
                lyrics = null,
                audioType = "recorded",
                audioPath = source.absolutePath,
                isFavorite = false
            )
        )
    }

    private fun digestOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun entry(title: String) = JSONObject()
        .put("title", title)
        .put("poet", JSONObject.NULL)
        .put("category", NaatCategories.NAAT)
        .put("lyrics", JSONObject.NULL)
        .put("audioType", "none")
        .put("audioPath", JSONObject.NULL)
        .put("isFavorite", false)
        .put("createdAt", 1L)

    private fun entriesOnlyDocument(audioPath: String, marker: Boolean = true): JSONObject {
        val doc = JSONObject()
            .put("formatVersion", 2)
            .put(
                "entries", JSONArray().put(
                    entry("Replaced")
                        .put("audioType", "recorded")
                        .put("audioPath", audioPath)
                )
            )
        if (marker) doc.put("entriesOnly", true)
        return doc
    }

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

    private fun zipNames(zipFile: File): List<String> {
        ZipFile(zipFile).use { zip ->
            return zip.entries().toList().map { it.name }
        }
    }

    private fun zipJson(zipFile: File): JSONObject {
        ZipFile(zipFile).use { zip ->
            val manifest = zip.getEntry("naatbook_data.json") ?: error("no manifest")
            return JSONObject(zip.getInputStream(manifest).readBytes().toString(Charsets.UTF_8))
        }
    }
}
