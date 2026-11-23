package ru.begunok.ui.history

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import ru.begunok.activity.ActivityCalendarPeriod
import ru.begunok.activity.ActivityCalendar
import ru.begunok.activity.DailyActivityRow
import ru.begunok.data.db.PcCalendarDay
import ru.begunok.health.HealthAutoSync
import ru.begunok.health.StepHistory
import java.time.LocalDate
import java.time.ZoneId

internal data class DailyActivityTableState(
    val rows: List<DailyActivityRow> = emptyList(),
    val pcDays: Map<LocalDate, PcCalendarDay> = emptyMap(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val stepsLoading: Boolean = false,
    val stepsMessage: String? = null,
    val selection: ActivityCalendarPeriod = ActivityCalendarPeriod(),
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeDailyActivity(
    calendar: ActivityCalendar,
    health: HealthAutoSync,
    selection: Flow<ActivityCalendarPeriod>,
    reload: Flow<Int>,
    healthChanges: Flow<Unit>,
): Flow<DailyActivityTableState> =
    combine(selection.distinctUntilChanged(), reload) { selected, _ -> selected }
        .flatMapLatest { selected ->
            flow {
                emit(DailyActivityTableState(selection = selected))
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val start = selected.start
                val end = minOf(selected.end, today)
                val steps: Flow<StepHistory> = healthChanges.flatMapLatest {
                    flow {
                        emit(StepHistory(days = health.dailySteps(start, end)))
                    }
                        .onStart { emit(StepHistory(loading = true)) }
                        .catch { emit(StepHistory(message = it.message ?: "Не удалось прочитать шаги.", failed = true)) }
                        .flowOn(Dispatchers.IO)
                }
                emitAll(
                    combine(calendar.phone, steps, calendar.observePc(start, end)) { data, dailySteps, pc ->
                        DailyActivityTableState(
                            rows = data.rows(dailySteps.days, start, end, zone),
                            pcDays = pc.associateBy { LocalDate.parse(it.day) },
                            loading = false,
                            stepsLoading = dailySteps.loading,
                            stepsMessage = dailySteps.message,
                            selection = selected,
                        )
                    },
                )
            }.catch {
                emit(DailyActivityTableState(loading = false, failed = true, selection = selected))
            }
        }
