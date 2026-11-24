package ru.begunok.ui.sleep

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import ru.begunok.health.HealthAutoSync
import ru.begunok.health.SleepReport
import java.time.ZoneId

internal sealed interface SleepState {
    data object Loading : SleepState
    data object Failed : SleepState
    data class Ready(val reports: List<SleepReport>, val zone: ZoneId) : SleepState
}

internal class SleepViewModel(health: HealthAutoSync) : ViewModel() {
    private val retries = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state = retries.flatMapLatest {
        health.historyChanges.map<Long, SleepState> {
            try {
                val zone = ZoneId.systemDefault()
                SleepState.Ready(health.sleepReports(zone), zone)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("BegunokHealth", "Cannot read retained sleep history", error)
                SleepState.Failed
            }
        }.onStart { emit(SleepState.Loading) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepState.Loading)

    fun retry() = retries.update { it + 1 }
}
