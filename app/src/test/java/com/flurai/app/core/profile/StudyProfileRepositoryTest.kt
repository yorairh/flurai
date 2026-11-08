package com.flurai.app.core.profile

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.flurai.app.core.model.JlptLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class StudyProfileRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repository(scope: CoroutineScope) = StudyProfileRepository(
        PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("test.preferences_pb").also { it.delete() } },
    )

    @Test
    fun missingProfileMeansNotOnboarded() = runTest(UnconfinedTestDispatcher()) {
        assertNull(repository(backgroundScope).profile("uid-1").first())
    }

    @Test
    fun profilesAreSavedPerUser() = runTest(UnconfinedTestDispatcher()) {
        val repo = repository(backgroundScope)
        val profile = StudyProfile(JlptLevel.N4, 30, 20, knowsHiragana = true, knowsKatakana = false)

        repo.save("uid-1", profile)

        assertEquals(profile, repo.profile("uid-1").first())
        assertNull(repo.profile("uid-2").first())
    }
}
