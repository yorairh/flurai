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
import com.flurai.app.core.activity.StudyDayDao
import com.flurai.app.core.quiz.TestResultDao
import com.flurai.app.core.study.MIGRATION_1_2
import com.flurai.app.core.study.MIGRATION_2_3
import com.flurai.app.core.study.MIGRATION_3_4
import com.flurai.app.core.study.MIGRATION_4_5
import com.flurai.app.core.study.MIGRATION_5_6
import com.flurai.app.core.video.CustomChannelDao
import com.flurai.app.core.grammar.GrammarProgressDao
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
        Room.databaseBuilder(context, UserDatabase::class.java, "user.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build()

    @Provides
    fun studyDao(db: UserDatabase): StudyDao = db.studyDao()

    @Provides
    fun studyDayDao(db: UserDatabase): StudyDayDao = db.studyDayDao()

    @Provides
    fun testResultDao(db: UserDatabase): TestResultDao = db.testResultDao()

    @Provides
    @Singleton
    fun firestore(): com.google.firebase.firestore.FirebaseFirestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()

    @Provides
    fun syncDao(db: UserDatabase): com.flurai.app.core.sync.SyncDao = db.syncDao()

    @Provides
    fun accountDao(db: UserDatabase): com.flurai.app.core.account.AccountDao = db.accountDao()

    @Provides
    fun customChannelDao(db: UserDatabase): CustomChannelDao = db.customChannelDao()

    @Provides
    fun deckDao(db: UserDatabase): com.flurai.app.core.study.DeckDao = db.deckDao()

    @Provides
    fun deckWords(dao: com.flurai.app.core.study.DeckDao): com.flurai.app.core.study.DeckWords =
        object : com.flurai.app.core.study.DeckWords {
            override suspend fun wordIds(deckId: Long) = dao.wordIds(deckId)
        }

    @Provides
    fun grammarProgressDao(db: UserDatabase): GrammarProgressDao = db.grammarProgressDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    abstract fun authRepository(impl: FirebaseAuthRepository): AuthRepository

    @Binds
    abstract fun quizBackend(impl: com.flurai.app.core.quiz.RepositoryQuizBackend): com.flurai.app.core.quiz.QuizBackend

    @Binds
    abstract fun cardContent(impl: com.flurai.app.core.study.DatabaseCardContent): com.flurai.app.core.study.CardContentSource

    @Binds
    abstract fun syncCloud(impl: com.flurai.app.core.sync.FirestoreCloud): com.flurai.app.core.sync.SyncCloud

    @Binds
    abstract fun syncLocal(impl: com.flurai.app.core.sync.RoomSyncLocal): com.flurai.app.core.sync.SyncLocal
}
