package com.flurai.app.ui.tests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.quiz.QuizRepository
import com.flurai.app.core.quiz.TestResultEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Past results, newest first, for the tests hub. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TestsHubViewModel @Inject constructor(auth: AuthRepository, quizzes: QuizRepository) : ViewModel() {
    val results: StateFlow<List<TestResultEntity>> = auth.currentUser.flatMapLatest { user ->
        if (user == null) emptyFlow() else quizzes.results(user.uid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
