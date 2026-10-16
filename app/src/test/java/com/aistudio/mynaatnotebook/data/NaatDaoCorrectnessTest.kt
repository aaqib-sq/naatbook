package com.aistudio.mynaatnotebook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NaatDaoCorrectnessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, NaatDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    @After fun close() = database.close()

    @Test
    fun `library search executes in Room and returns lightweight summaries`() = runBlocking {
        val dao = database.naatDao()
        dao.insertNaat(
            NaatEntity(
                title = "Searchable title",
                poet = "Poet",
                category = NaatCategories.NAAT,
                lyrics = "unique lyrics token " + "x".repeat(10_000),
                audioType = "none",
                audioPath = null,
                isFavorite = false
            )
        )

        val results = dao.getFilteredSummaries("unique lyrics token", null, false).first()

        assertTrue(results.single().title == "Searchable title")
    }

    @Test
    fun `recent query stays bounded to ten and falls back to edit order when never opened`() =
        runBlocking {
            val dao = database.naatDao()
            repeat(12) { index ->
                dao.insertNaat(
                    NaatEntity(
                        title = "Recent $index",
                        poet = null,
                        category = NaatCategories.NAAT,
                        lyrics = null,
                        audioType = "none",
                        audioPath = null,
                        isFavorite = false,
                        createdAt = 1L,
                        updatedAt = index.toLong()
                    )
                )
            }

            val recent = dao.getRecentSummaries().first()

            assertTrue(recent.size == 10)
            assertTrue(recent.first().title == "Recent 11")
            assertTrue(recent.last().title == "Recent 2")
        }

    @Test
    fun `recent query orders by last opened and editing alone no longer reorders`() = runBlocking {
        val dao = database.naatDao()
        val oldId = dao.insertNaat(
            NaatEntity(
                title = "Old entry",
                poet = null,
                category = NaatCategories.NAAT,
                lyrics = "first line\nsecond line",
                audioType = "none",
                audioPath = null,
                isFavorite = false,
                createdAt = 1L,
                updatedAt = 1L
            )
        ).toInt()
        dao.insertNaat(
            NaatEntity(
                title = "Newer entry",
                poet = null,
                category = NaatCategories.NAAT,
                lyrics = null,
                audioType = "none",
                audioPath = null,
                isFavorite = false,
                createdAt = 1L,
                updatedAt = 100L
            )
        )

        // Before any open, the newer edit leads (unchanged fallback behavior).
        assertTrue(dao.getRecentSummaries().first().first().title == "Newer entry")

        dao.markOpened(oldId, 10_000L)

        val recent = dao.getRecentSummaries().first()
        // The opened entry now leads...
        assertTrue(recent.first().title == "Old entry")
        // ...and the stamp did not rewrite edit time.
        assertTrue(dao.getNaatById(oldId)!!.updatedAt == 1L)
        // The recents summary carries lyrics for the first-line preview.
        assertTrue(recent.first().lyrics == "first line\nsecond line")
    }

    @Test
    fun `markOpened only touches its own column`() = runBlocking {
        val dao = database.naatDao()
        val id = dao.insertNaat(
            NaatEntity(
                title = "Stamp check",
                poet = null,
                category = NaatCategories.NAAT,
                lyrics = null,
                audioType = "none",
                audioPath = null,
                isFavorite = false,
                createdAt = 5L,
                updatedAt = 7L
            )
        ).toInt()

        assertTrue(dao.markOpened(id, 9L) == 1)

        val row = dao.getNaatById(id)!!
        assertTrue(row.lastOpenedAt == 9L)
        assertTrue(row.updatedAt == 7L)
        assertTrue(row.createdAt == 5L)
    }

    @Test
    fun `favorite toggle uses current database value on every rapid call`() = runBlocking {
        val dao = database.naatDao()
        val id = dao.insertNaat(
            NaatEntity(
                title = "Atomic favorite",
                poet = null,
                category = NaatCategories.NAAT,
                lyrics = null,
                audioType = "none",
                audioPath = null,
                isFavorite = false
            )
        ).toInt()

        dao.toggleFavorite(id)
        assertTrue(dao.getNaatById(id)!!.isFavorite)
        dao.toggleFavorite(id)
        assertFalse(dao.getNaatById(id)!!.isFavorite)
    }
}
