package com.aistudio.mynaatnotebook.di

import android.content.Context
import androidx.room.Room
import com.aistudio.mynaatnotebook.data.NaatDao
import com.aistudio.mynaatnotebook.data.NaatDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideNaatDatabase(@ApplicationContext context: Context): NaatDatabase =
        Room.databaseBuilder(
            context,
            NaatDatabase::class.java,
            "naat_notebook_database"
        )
            .addMigrations(
                NaatDatabase.MIGRATION_1_2,
                NaatDatabase.MIGRATION_2_3,
                NaatDatabase.MIGRATION_3_4,
                NaatDatabase.MIGRATION_4_5,
                NaatDatabase.MIGRATION_5_6,
                NaatDatabase.MIGRATION_6_7
            )
            // Never silently erase a notebook. A missing future migration must fail.
            .build()

    @Provides
    fun provideNaatDao(database: NaatDatabase): NaatDao = database.naatDao()
}
