package com.flurai.app.ui.onboarding

import com.flurai.app.core.auth.AuthUser
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.ui.AppState
import com.flurai.app.ui.appStateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingUiStateTest {
    @Test
    fun kanaStepOnlyForBeginners() {
        assertEquals(OnboardingStep.entries, OnboardingUiState(level = JlptLevel.Beginner).steps)
        assertEquals(
            listOf(OnboardingStep.Level, OnboardingStep.Goal),
            OnboardingUiState(level = JlptLevel.N5).steps,
        )
    }

    @Test
    fun cannotContinueWithoutALevel() {
        assertFalse(OnboardingUiState().canContinue)
        assertTrue(OnboardingUiState(level = JlptLevel.N3).canContinue)
    }

    @Test
    fun nonBeginnersAreAssumedToReadKana() {
        val profile = OnboardingUiState(level = JlptLevel.N4, knowsHiragana = false).toProfile()
        assertTrue(profile.knowsHiragana && profile.knowsKatakana)

        val beginner = OnboardingUiState(level = JlptLevel.Beginner, knowsHiragana = true).toProfile()
        assertTrue(beginner.knowsHiragana)
        assertFalse(beginner.knowsKatakana)
    }

    @Test
    fun appStateFollowsUserAndProfile() {
        val user = AuthUser("uid", "Aki", "aki@example.com")
        val profile = StudyProfile(JlptLevel.N5, 15, 10, knowsHiragana = true, knowsKatakana = true)
        assertEquals(AppState.SignedOut, appStateOf(null, profile))
        assertEquals(AppState.NeedsOnboarding(user), appStateOf(user, null))
        assertEquals(AppState.Ready(user, profile), appStateOf(user, profile))
    }
}
