package com.flurai.app.ui.onboarding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.core.profile.StudyProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class EditProfileTest {
    @get:Rule val tmp = TemporaryFolder()

    // Plain Unconfined (not a test dispatcher): the save resumes on DataStore's IO thread, and a
    // test dispatcher would queue that resumption on a scheduler nothing is advancing.
    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun editingStartsFromTheSavedProfileAndSavesChanges() = runBlocking {
        val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val repo = StudyProfileRepository(
            PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                tmp.newFile("edit.preferences_pb").also { it.delete() }
            },
        )
        val original = StudyProfile(JlptLevel.N5, 15, 10, knowsHiragana = true, knowsKatakana = true)
        // Only one write per test: DataStore replaces its file by renaming, which fails on Windows
        // when the file already exists (Android is fine).
        val vm = OnboardingViewModel(repo)

        vm.startEditing(original)
        assertEquals(JlptLevel.N5, vm.state.value.level)
        assertEquals(15, vm.state.value.dailyMinutes)

        vm.selectLevel(JlptLevel.N4)
        vm.setDailyMinutes(30)
        vm.startEditing(original) // a second call (e.g. recomposition) must not wipe the edits
        vm.saveEdits("uid")

        // DataStore writes the file on a background thread, so wait for the save to finish.
        assertTrue(withTimeout(5_000) { vm.state.first { it.saved } }.saved)
        assertEquals(original.copy(level = JlptLevel.N4, dailyMinutes = 30), repo.profile("uid").first())
        dataStoreScope.cancel()
    }
}
