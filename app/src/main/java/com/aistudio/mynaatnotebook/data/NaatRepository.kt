package com.aistudio.mynaatnotebook.data

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NaatRepository @Inject constructor(private val naatDao: NaatDao) {
    val allNaats: Flow<List<NaatEntity>> = naatDao.getAllNaats()
    val allSummaries: Flow<List<NaatSummary>> = naatDao.getAllSummaries()
    val recentSummaries: Flow<List<NaatSummary>> = naatDao.getRecentSummaries()
    val categoryCounts: Flow<List<CategoryCount>> = naatDao.getCategoryCounts()

    fun filteredSummaries(
        query: String,
        folder: String?,
        favoritesOnly: Boolean
    ): Flow<List<NaatSummary>> =
        naatDao.getFilteredSummaries(escapeLikeWildcards(query), folder, favoritesOnly)

    companion object {
        /**
         * Escapes LIKE wildcards so a literal '%' or '_' in the user's search
         * is matched literally, not as a wildcard. Must stay in sync with the
         * ESCAPE '\' clause in [NaatDao.getFilteredSummaries].
         */
        fun escapeLikeWildcards(query: String): String =
            query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }

    fun getNaatByIdFlow(id: Int): Flow<NaatEntity?> = naatDao.getNaatByIdFlow(id)

    suspend fun getNaatById(id: Int): NaatEntity? = naatDao.getNaatById(id)

    /** Identity-only rows for the duplicate check (audit 05 M2). */
    suspend fun allIdentityRows(): List<EntryIdentityRow> = naatDao.allIdentityRows()

    suspend fun insert(naat: NaatEntity): Long = naatDao.insertNaat(naat)

    suspend fun insertAll(naats: List<NaatEntity>): List<Long> = naatDao.insertNaats(naats)

    /**
     * Audit fix (stage 1): REPLACE restore — inserts the new rows and deletes
     * the old ones inside one Room transaction (see [NaatDao.replaceLibrary]).
     */
    suspend fun replaceLibrary(newNaats: List<NaatEntity>, oldIds: List<Int>): List<Long> =
        naatDao.replaceLibrary(newNaats, oldIds)

    suspend fun update(naat: NaatEntity): Int = naatDao.updateNaat(naat)

    suspend fun toggleFavorite(id: Int): NaatEntity? {
        naatDao.toggleFavorite(id)
        return naatDao.getNaatById(id)
    }

    /** Records a Reader open for the recents strip; never touches updatedAt. */
    suspend fun markOpened(id: Int): Int =
        naatDao.markOpened(id, System.currentTimeMillis())

    suspend fun delete(naat: NaatEntity) = naatDao.deleteNaat(naat)

    suspend fun deleteById(id: Int) = naatDao.deleteNaatById(id)

    suspend fun countByAudioPath(audioPath: String): Int = naatDao.countByAudioPath(audioPath)
}
