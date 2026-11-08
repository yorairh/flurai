package com.flurai.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.core.profile.StudyProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep { Level, Kana, Goal }

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Level,
    val level: JlptLevel? = null,
    val knowsHiragana: Boolean = false,
    val knowsKatakana: Boolean = false,
    val dailyMinutes: Int = StudyProfile.DEFAULT_DAILY_MINUTES,
    val newCardsPerDay: Int = StudyProfile.DEFAULT_NEW_CARDS,
    val saving: Boolean = false,
) {
    /** The kana question only makes sense for beginners; everyone above can read kana. */
    val steps: List<OnboardingStep>
        get() = if (level == JlptLevel.Beginner) OnboardingStep.entries
        else listOf(OnboardingStep.Level, OnboardingStep.Goal)

    val stepIndex: Int get() = steps.indexOf(step)
    val canContinue: Boolean get() = step != OnboardingStep.Level || level != null

    fun toProfile(): StudyProfile {
        val beginner = level == JlptLevel.Beginner
        return StudyProfile(
            level = requireNotNull(level),
            dailyMinutes = dailyMinutes,
            newCardsPerDay = newCardsPerDay,
            knowsHiragana = !beginner || knowsHiragana,
            knowsKatakana = !beginner || knowsKatakana,
        )
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val profiles: StudyProfileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun selectLevel(level: JlptLevel) = _state.update { it.copy(level = level) }
    fun setKnowsHiragana(value: Boolean) = _state.update { it.copy(knowsHiragana = value) }
    fun setKnowsKatakana(value: Boolean) = _state.update { it.copy(knowsKatakana = value) }
    fun setDailyMinutes(value: Int) = _state.update { it.copy(dailyMinutes = value) }
    fun setNewCardsPerDay(value: Int) = _state.update { it.copy(newCardsPerDay = value) }

    /** Moves to the next step, or saves the profile on the last one (which ends onboarding). */
    fun next(uid: String) {
        val s = _state.value
        if (!s.canContinue || s.saving) return
        val nextStep = s.steps.getOrNull(s.stepIndex + 1)
        if (nextStep != null) {
            _state.update { it.copy(step = nextStep) }
        } else {
            _state.update { it.copy(saving = true) }
            viewModelScope.launch { profiles.save(uid, s.toProfile()) }
        }
    }

    /** Returns false when already on the first step. */
    fun back(): Boolean {
        val s = _state.value
        val previous = s.steps.getOrNull(s.stepIndex - 1) ?: return false
        _state.update { it.copy(step = previous) }
        return true
    }
}
