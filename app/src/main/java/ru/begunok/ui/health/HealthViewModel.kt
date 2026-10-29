package ru.begunok.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.begunok.health.HealthAvailability
import ru.begunok.health.HealthDay
import ru.begunok.health.HealthRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HealthUi(
    val availability: HealthAvailability,
    val granted: Set<String> = emptySet(),
    val day: HealthDay? = null,
    val loading: Boolean = false,
)

class HealthViewModel(private val repository: HealthRepository) : ViewModel() {
    val permissions: Set<String> = repository.permissions

    private val _ui = MutableStateFlow(HealthUi(repository.availability()))
    val ui: StateFlow<HealthUi> = _ui.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val availability = repository.availability()
            _ui.value = _ui.value.copy(availability = availability, loading = true)
            val granted = repository.granted()
            val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val day = if (granted.isEmpty()) null else repository.day(dayStart, Instant.now(), granted)
            _ui.value = HealthUi(availability, granted, day, loading = false)
        }
    }
}
