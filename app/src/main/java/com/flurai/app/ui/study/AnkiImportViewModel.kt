package com.flurai.app.ui.study

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.flurai.app.core.activity.SettingsRepository
import com.flurai.app.core.anki.AnkiException
import com.flurai.app.core.anki.AnkiImportOptions
import com.flurai.app.core.anki.AnkiImportRepository
import com.flurai.app.core.anki.AnkiImportResult
import com.flurai.app.core.anki.AnkiImporter
import com.flurai.app.core.anki.AnkiPlan
import com.flurai.app.core.anki.NoteTypeChoice
import com.flurai.app.core.anki.planImport
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.auth.uid
import com.flurai.app.ui.AnkiImportRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

sealed interface AnkiImportState {
    data object Reading : AnkiImportState
    data class Failed(val message: String) : AnkiImportState
    data class Confirm(val plan: AnkiPlan, val options: AnkiImportOptions) : AnkiImportState
    data class Importing(val done: Int, val total: Int) : AnkiImportState
    data class Done(val result: AnkiImportResult) : AnkiImportState
}

/** Reads the Anki file, shows what will come over (with the choices), and imports it. */
@HiltViewModel
class AnkiImportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val repository: AnkiImportRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val uri = Uri.parse(savedStateHandle.toRoute<AnkiImportRoute>().uri)
    private val _state = MutableStateFlow<AnkiImportState>(AnkiImportState.Reading)
    val state: StateFlow<AnkiImportState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = try {
                val collection = repository.read(uri)
                val plan = withContext(Dispatchers.Default) { planImport(collection, Instant.now()) }
                if (plan.noteTypes.isEmpty()) AnkiImportState.Failed("This Anki file has no notes in it.")
                else AnkiImportState.Confirm(plan, plan.defaults)
            } catch (e: AnkiException) {
                AnkiImportState.Failed(e.message ?: "Could not read this file.")
            } catch (e: Exception) {
                AnkiImportState.Failed("Could not read this file as an Anki deck.")
            }
        }
    }

    fun setOptions(change: (AnkiImportOptions) -> AnkiImportOptions) = _state.update {
        if (it is AnkiImportState.Confirm) it.copy(options = change(it.options)) else it
    }

    fun setNoteType(id: Long, change: (NoteTypeChoice) -> NoteTypeChoice) = setOptions { o ->
        o.copy(noteTypes = o.noteTypes + (id to change(o.noteTypes.getValue(id))))
    }

    fun import() {
        val confirm = _state.value as? AnkiImportState.Confirm ?: return
        val uid = auth.uid ?: return
        _state.value = AnkiImportState.Importing(0, confirm.plan.collection.notes.size)
        viewModelScope.launch {
            _state.value = try {
                val listening = settings.settings(uid).first().listeningCards
                val result = withContext(Dispatchers.IO) {
                    AnkiImporter(repository).import(uid, confirm.plan, confirm.options, Instant.now(), listening) { done, total ->
                        _state.value = AnkiImportState.Importing(done, total)
                    }
                }
                AnkiImportState.Done(result)
            } catch (e: Exception) {
                AnkiImportState.Failed("The import stopped: ${e.message ?: e.javaClass.simpleName}. Nothing was lost; try again.")
            }
        }
    }
}
