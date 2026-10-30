package ru.begunok.ui.weight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.begunok.data.SettingsRepository
import ru.begunok.data.WeightRepository
import ru.begunok.weight.GoalStatus
import ru.begunok.weight.WeightGoal
import ru.begunok.weight.WeightPoint
import ru.begunok.weight.WeightStats
import java.time.LocalDate

data class WeightOverview(
    val points: List<WeightPoint>,
    val smoothed: List<Double>,
    val weekChange: Double?,
    val trendPerWeek: Double?,
    val goal: WeightGoal?,
    val status: GoalStatus?,
) {
    val current: WeightPoint? get() = points.lastOrNull()
}

class WeightViewModel(
    private val repository: WeightRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val overview: StateFlow<WeightOverview?> = combine(repository.observeEntries(), settings.settings) { entries, prefs ->
        val points = entries.map { WeightPoint(LocalDate.ofEpochDay(it.day), it.kg) }
        val trend = WeightStats.trendPerDay(points)
        val goal = prefs.weightGoal
        WeightOverview(
            points = points,
            smoothed = WeightStats.smoothed(points),
            weekChange = WeightStats.weekChange(points),
            trendPerWeek = trend?.times(7),
            goal = goal,
            status = points.lastOrNull()?.let { last -> goal?.let { WeightStats.status(it, last.kg, trend, LocalDate.now()) } },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun record(day: LocalDate, kg: Double) {
        viewModelScope.launch { repository.record(day, kg) }
    }

    fun delete(day: LocalDate) {
        viewModelScope.launch { repository.delete(day) }
    }

    fun setGoal(targetKg: Double, deadline: LocalDate?) {
        val current = overview.value?.current?.kg ?: return
        val goal = WeightStats.newGoal(overview.value?.goal, targetKg, deadline, current)
        viewModelScope.launch { settings.setWeightGoal(goal) }
    }

    fun clearGoal() {
        viewModelScope.launch { settings.setWeightGoal(null) }
    }
}
