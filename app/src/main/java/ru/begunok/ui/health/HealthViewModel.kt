package ru.begunok.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.begunok.health.HealthAvailability
import ru.begunok.health.HealthDay
import ru.begunok.health.HealthRepository
import ru.begunok.health.HealthReadException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HealthUi(
    val availability: HealthAvailability,
    val granted: Set<String> = emptySet(),
    val day: HealthDay? = null,
    val loading: Boolean = false,
    val message: String? = null,
)

class HealthViewModel(private val repository: HealthRepository) : ViewModel() {
    val permissions: Set<String> get() = repository.permissions

    private val _ui = MutableStateFlow(HealthUi(repository.availability()))
    val ui: StateFlow<HealthUi> = _ui.asStateFlow()
    private var refreshJob: Job? = null

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val availability = repository.availability()
            _ui.value = HealthUi(availability, loading = true)
            try {
                val granted = repository.granted()
                _ui.value = _ui.value.copy(granted = granted)
                val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
                val day = if (granted.isEmpty()) null else repository.day(dayStart, Instant.now(), granted)
                _ui.value = HealthUi(availability, granted, day)
            } catch (error: HealthReadException) {
                _ui.value = _ui.value.copy(loading = false, message = error.message)
            }
        }
    }
}
