package ru.begunok.ui.math

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.begunok.data.MathCatalog
import ru.begunok.data.MathProgress
import ru.begunok.data.MathRepository
import ru.begunok.math.MathRating

data class MathUiState(
    val catalog: MathCatalog? = null,
    val progress: MathProgress = MathProgress(),
    val loading: Boolean = true,
    val loadError: Boolean = false,
    val saving: Boolean = false,
    val saveError: Boolean = false,
    val notice: String? = null,
)

class MathViewModel(private val repository: MathRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(MathUiState())
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var retrySave: (() -> Unit)? = null

    init { reload() }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = true, loadError = false) }
            try {
                val catalog = repository.catalog()
                repository.observeProgress(catalog).collect { progress ->
                    mutableState.update {
                        it.copy(catalog = catalog, progress = progress, loading = false, loadError = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, loadError = true) }
            }
        }
    }

    fun openLesson(id: String, onSaved: (String) -> Unit) =
        save({ repository.saveProgress(id) }) { onSaved(id) }

    fun setCompleted(id: String, completed: Boolean) =
        save({ repository.saveProgress(id, completed) }) {}

    fun recordTask(id: String) = save({ repository.recordTask(id) }) {
        mutableState.update { it.copy(notice = "Одна решённая вами задача записана.") }
    }

    fun rateReview(id: String, rating: MathRating, onSaved: () -> Unit) =
        save({ repository.rateReview(id, rating) }) {
            mutableState.update {
                it.copy(notice = if (rating == MathRating.Again || rating == MathRating.Hard) {
                    "Повторение записано. Перечитайте правило и потренируйтесь сейчас. Ещё одна оценка сегодня недоступна."
                } else {
                    "Повторение записано; следующая дата рассчитана на основе SM-2."
                })
            }
            onSaved()
        }

    fun dismissNotice() { mutableState.update { it.copy(notice = null) } }

    fun retryProgress() { retrySave?.invoke() }

    private fun save(operation: suspend () -> Unit, onSaved: () -> Unit) {
        if (mutableState.value.saving) return
        mutableState.update { it.copy(saving = true, saveError = false) }
        retrySave = { save(operation, onSaved) }
        viewModelScope.launch {
            try {
                operation()
                retrySave = null
                mutableState.update { it.copy(saving = false, saveError = false) }
                onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableState.update { it.copy(saving = false, saveError = true) }
            }
        }
    }
}
