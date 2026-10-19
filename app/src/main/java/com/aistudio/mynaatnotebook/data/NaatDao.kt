package com.aistudio.mynaatnotebook.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NaatDao {
    @Query("SELECT * FROM naats ORDER BY createdAt DESC")
    fun getAllNaats(): Flow<List<NaatEntity>>

    @Query("""
        SELECT id, title, poet, category, audioType, audioPath, isFavorite, createdAt,
               secondaryAudioType, secondaryAudioPath
        FROM naats ORDER BY createdAt DESC
    """)
    fun getAllSummaries(): Flow<List<NaatSummary>>

    /** Truthful "recently opened" feed: entries rise by their last Reader open,
     *  falling back to edit time for entries never opened since this field existed. */
    @Query("""
        SELECT id, title, poet, category, lyrics, audioType, audioPath, isFavorite, createdAt,
               secondaryAudioType, secondaryAudioPath, titleFontOverride
        FROM naats
        ORDER BY COALESCE(lastOpenedAt, updatedAt) DESC
        LIMIT 10
    """)
    fun getRecentSummaries(): Flow<List<NaatSummary>>

    @Query("SELECT category, COUNT(*) AS count FROM naats GROUP BY category")
    fun getCategoryCounts(): Flow<List<CategoryCount>>

    // Audit 03 M2: the stored columns are folded in SQL with exactly the
    // rules normalizeSearchText applies to the query (harakat stripped,
    // variant letters folded, ASCII lowered) — a query typed with
    // diacritics or Arabic yeh/kaf used to match nothing at all.
    @Query("""
        SELECT id, title, poet, category, audioType, audioPath, isFavorite, createdAt,
               secondaryAudioType, secondaryAudioPath
        FROM naats
        WHERE (:folder IS NULL OR category = :folder COLLATE NOCASE)
          AND (:favoritesOnly = 0 OR isFavorite = 1)
          AND (:query = '' OR lower(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(title, 'ـ', ''), 'ً', ''), 'ٌ', ''), 'ٍ', ''), 'َ', ''), 'ُ', ''), 'ِ', ''), 'ّ', ''), 'ْ', ''), 'ٰ', ''), 'آ', 'ا'), 'أ', 'ا'), 'إ', 'ا'), 'ٱ', 'ا'), 'ؤ', 'و'), 'ئ', 'ی'), 'ي', 'ی'), 'ى', 'ی'), 'ك', 'ک'), 'ة', 'ہ'), 'ھ', 'ہ')) LIKE '%' || :query || '%' ESCAPE '\'
               OR lower(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(poet, 'ـ', ''), 'ً', ''), 'ٌ', ''), 'ٍ', ''), 'َ', ''), 'ُ', ''), 'ِ', ''), 'ّ', ''), 'ْ', ''), 'ٰ', ''), 'آ', 'ا'), 'أ', 'ا'), 'إ', 'ا'), 'ٱ', 'ا'), 'ؤ', 'و'), 'ئ', 'ی'), 'ي', 'ی'), 'ى', 'ی'), 'ك', 'ک'), 'ة', 'ہ'), 'ھ', 'ہ')) LIKE '%' || :query || '%' ESCAPE '\'
               OR lower(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(lyrics, 'ـ', ''), 'ً', ''), 'ٌ', ''), 'ٍ', ''), 'َ', ''), 'ُ', ''), 'ِ', ''), 'ّ', ''), 'ْ', ''), 'ٰ', ''), 'آ', 'ا'), 'أ', 'ا'), 'إ', 'ا'), 'ٱ', 'ا'), 'ؤ', 'و'), 'ئ', 'ی'), 'ي', 'ی'), 'ى', 'ی'), 'ك', 'ک'), 'ة', 'ہ'), 'ھ', 'ہ')) LIKE '%' || :query || '%' ESCAPE '\')
        ORDER BY createdAt DESC
    """)
    fun getFilteredSummaries(
        query: String,
        folder: String?,
        favoritesOnly: Boolean
    ): Flow<List<NaatSummary>>

    @Query("SELECT * FROM naats WHERE id = :id")
    suspend fun getNaatById(id: Int): NaatEntity?

    @Query("SELECT * FROM naats WHERE id = :id")
    fun getNaatByIdFlow(id: Int): Flow<NaatEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNaat(naat: NaatEntity): Long

    /** One generated Room transaction: either the complete restore is inserted or none is. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNaats(naats: List<NaatEntity>): List<Long>

    /**
     * Audit fix (stage 1): REPLACE-mode restores used to delete the old rows
     * one-by-one *after* the new rows were committed, outside any shared
     * transaction — an interruption mid-delete could leave a mixed old+new
     * library. The inserts and the old-row deletes now share one transaction.
     */
    @Transaction
    suspend fun replaceLibrary(newNaats: List<NaatEntity>, oldIds: List<Int>): List<Long> {
        val rowIds = insertNaats(newNaats)
        oldIds.forEach { deleteNaatById(it) }
        return rowIds
    }

    @Update
    suspend fun updateNaat(naat: NaatEntity): Int

    /** Toggles from the value currently stored in Room, never from a stale UI snapshot. */
    @Query("UPDATE naats SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: Int): Int

    /** Stamps the Reader-open moment; deliberately does NOT touch updatedAt (edit time). */
    @Query("UPDATE naats SET lastOpenedAt = :at WHERE id = :id")
    suspend fun markOpened(id: Int, at: Long): Int

    @Delete
    suspend fun deleteNaat(naat: NaatEntity)

    @Query("DELETE FROM naats WHERE id = :id")
    suspend fun deleteNaatById(id: Int)

    @Query("SELECT COUNT(*) FROM naats WHERE audioPath = :audioPath OR secondaryAudioPath = :audioPath")
    suspend fun countByAudioPath(audioPath: String): Int
}
