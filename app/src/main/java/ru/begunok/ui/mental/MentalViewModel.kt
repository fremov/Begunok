package ru.begunok.ui.mental

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ru.begunok.data.MentalRepository
import ru.begunok.mental.MentalResult
import ru.begunok.mental.MentalTest
import ru.begunok.mental.QuickCheckIn


data class MentalUiState(
    val loading: Boolean = true,
    val results: List<MentalResult> = emptyList(),
    val checkIns: List<QuickCheckIn> = emptyList(),
    val error: String? = null,
)

data class MentalCompletion(val test: MentalTest, val answers: List<Int>, val completedAt: Long)

data class MentalSaveState(
    val saving: Boolean = false,
    val error: String? = null,
    val completion: MentalCompletion? = null,
)

data class CheckInSaveState(
    val saving: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false,
)

data class MentalDeleteState(
    val deleting: Boolean = false,
    val error: String? = null,
    val deletedId: Long? = null,
)

class MentalViewModel(
    private val repository: MentalRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MentalUiState())
    val uiState = _uiState.asStateFlow()
    private val _saveState = MutableStateFlow(MentalSaveState())
    val saveState = _saveState.asStateFlow()
    private val _deleteState = MutableStateFlow(MentalDeleteState())
    val deleteState = _deleteState.asStateFlow()
    private val _checkInSaveState = MutableStateFlow(CheckInSaveState())
    val checkInSaveState = _checkInSaveState.asStateFlow()
    private val _checkInDeleteState = MutableStateFlow(MentalDeleteState())
    val checkInDeleteState = _checkInDeleteState.asStateFlow()
    private var observation: Job? = null

    init {
        retry()
    }

    fun retry() {
        observation?.cancel()
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        observation = viewModelScope.launch {
            try {
                combine(repository.observeResults(), repository.observeCheckIns()) { results, checkIns ->
                    MentalUiState(loading = false, results = results, checkIns = checkIns)
                }.collect { _uiState.value = it }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = "Не удалось загрузить историю самочувствия. Попробуйте ещё раз.",
                )
            }
        }
    }

    fun save(test: MentalTest, answers: List<Int>) {
        if (_saveState.value.saving || _saveState.value.completion != null) return
        // Freeze the submitted answers while the draft remains editable after a failed write.
        val submitted = answers.toList()
        if (submitted.size != test.questions.size || submitted.any { it !in test.options.indices }) return
        _saveState.value = MentalSaveState(saving = true)
        viewModelScope.launch {
            try {
                val completedAt = System.currentTimeMillis()
                repository.record(test, submitted, completedAt)
                _saveState.value = MentalSaveState(completion = MentalCompletion(test, submitted, completedAt))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _saveState.value = MentalSaveState(error = "Результат не сохранён. Ответы остались здесь — попробуйте сохранить ещё раз.")
            }
        }
    }

    fun resetSave() {
        if (!_saveState.value.saving) _saveState.value = MentalSaveState()
    }

    fun delete(id: Long) {
        if (_deleteState.value.deleting) return
        _deleteState.value = MentalDeleteState(deleting = true)
        viewModelScope.launch {
            try {
                repository.delete(id)
                _deleteState.value = MentalDeleteState(deletedId = id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _deleteState.value = MentalDeleteState(error = "Не удалось удалить запись. Попробуйте ещё раз.")
            }
        }
    }

    fun resetDelete() {
        if (!_deleteState.value.deleting) _deleteState.value = MentalDeleteState()
    }

    fun saveCheckIn(mood: Int, tension: Int, energy: Int, context: String?) {
        if (_checkInSaveState.value.saving || _checkInSaveState.value.completed) return
        if (mood !in 0..10 || tension !in 0..10 || energy !in 0..10) return
        _checkInSaveState.value = CheckInSaveState(saving = true)
        viewModelScope.launch {
            try {
                repository.recordCheckIn(mood, tension, energy, context)
                _checkInSaveState.value = CheckInSaveState(completed = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _checkInSaveState.value = CheckInSaveState(
                    error = "Не удалось сохранить отметку. Ответы остались здесь — попробуйте ещё раз.",
                )
            }
        }
    }

    fun resetCheckInSave() {
        if (!_checkInSaveState.value.saving) _checkInSaveState.value = CheckInSaveState()
    }

    fun deleteCheckIn(id: Long) {
        if (_checkInDeleteState.value.deleting) return
        _checkInDeleteState.value = MentalDeleteState(deleting = true)
        viewModelScope.launch {
            try {
                repository.deleteCheckIn(id)
                _checkInDeleteState.value = MentalDeleteState(deletedId = id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _checkInDeleteState.value = MentalDeleteState(error = "Не удалось удалить отметку. Попробуйте ещё раз.")
            }
        }
    }

    fun resetCheckInDelete() {
        if (!_checkInDeleteState.value.deleting) _checkInDeleteState.value = MentalDeleteState()
    }
}
