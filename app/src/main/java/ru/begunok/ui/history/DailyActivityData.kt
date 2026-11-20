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
import ru.begunok.activity.DailyActivity
import ru.begunok.activity.DailyActivityRow
import ru.begunok.data.MathRepository
import ru.begunok.data.ReadingRepository
import ru.begunok.data.ShowerRepository
import ru.begunok.data.WorkoutRepository
import ru.begunok.data.db.ColdShowerEntity
import ru.begunok.data.db.ReadingEntryEntity
import ru.begunok.data.db.WorkoutEntity
import ru.begunok.health.HealthAutoSync
import ru.begunok.health.StepHistory
import ru.begunok.math.MathActivityEvent
import ru.begunok.workout.Period
import java.time.LocalDate
import java.time.ZoneId

internal data class DailyActivityTableState(
    val rows: List<DailyActivityRow> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val stepsLoading: Boolean = false,
    val stepsMessage: String? = null,
    val selection: ActivityCalendarPeriod = ActivityCalendarPeriod(),
)

private data class LocalActivity(
    val workouts: List<WorkoutEntity>,
    val reading: List<ReadingEntryEntity>,
    val learning: List<MathActivityEvent>,
    val showers: List<ColdShowerEntity>,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeDailyActivity(
    workouts: WorkoutRepository,
    reading: ReadingRepository,
    math: MathRepository,
    showers: ShowerRepository,
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
                val learning = flow { emit(math.catalog()) }
                    .flatMapLatest { math.observeProgress(it) }
                val local = combine(
                    workouts.observeFinished(),
                    reading.observeEntries(),
                    learning,
                    showers.observeAll(),
                ) { sessions, pages, progress, cold ->
                    LocalActivity(sessions, pages, progress.events, cold)
                }
                val steps: Flow<StepHistory> = healthChanges.flatMapLatest {
                    flow {
                        emit(StepHistory(days = health.dailySteps(start, end)))
                    }
                        .onStart { emit(StepHistory(loading = true)) }
                        .catch { emit(StepHistory(message = it.message ?: "Не удалось прочитать шаги.", failed = true)) }
                        .flowOn(Dispatchers.IO)
                }
                emitAll(
                    combine(local, steps) { data, dailySteps ->
                        DailyActivityTableState(
                            rows = buildList {
                                val rowsByDay = DailyActivity.build(
                                    workouts = data.workouts,
                                    reading = data.reading,
                                    learning = data.learning,
                                    showers = data.showers,
                                    steps = dailySteps.days,
                                    today = end,
                                    period = Period.All,
                                    zone = zone,
                                ).filter { it.day >= start && it.day <= end }.associateBy { it.day }
                                var day = end
                                while (day >= start) {
                                    add(rowsByDay[day] ?: DailyActivityRow(day = day))
                                    day = day.minusDays(1)
                                }
                            },
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
