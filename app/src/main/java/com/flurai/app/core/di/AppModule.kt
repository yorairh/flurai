package com.flurai.app.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.auth.FirebaseAuthRepository
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.ContentDatabase
import com.flurai.app.core.study.StudyDao
import com.flurai.app.core.study.UserDatabase
import androidx.room.Room
import com.google.firebase.auth.FirebaseAuth
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun firebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    /**
     * Read-only study content copied from assets/content.db. When ContentDatabase.version goes up,
     * the old copy is thrown away and the new asset copied in (it holds no user data).
     */
    @Provides
    @Singleton
    fun contentDatabase(@ApplicationContext context: Context): ContentDatabase =
        Room.databaseBuilder(context, ContentDatabase::class.java, "content.db")
            .createFromAsset(ContentDatabase.ASSET_PATH)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun contentDao(db: ContentDatabase): ContentDao = db.contentDao()

    /** The learner's cards and review history. Never destructive: schema changes need migrations. */
    @Provides
    @Singleton
    fun userDatabase(@ApplicationContext context: Context): UserDatabase =
        Room.databaseBuilder(context, UserDatabase::class.java, "user.db").build()

    @Provides
    fun studyDao(db: UserDatabase): StudyDao = db.studyDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    abstract fun authRepository(impl: FirebaseAuthRepository): AuthRepository
}
