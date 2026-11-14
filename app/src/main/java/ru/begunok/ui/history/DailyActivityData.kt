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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import ru.begunok.activity.ActivityCalendarPeriod
import ru.begunok.activity.DailyActivity
import ru.begunok.activity.DailyActivityRow
import ru.begunok.activity.DailyGameActivity
import ru.begunok.data.GameActivityRepository
import ru.begunok.data.MathRepository
import ru.begunok.data.ReadingRepository
import ru.begunok.data.ShowerRepository
import ru.begunok.data.WorkoutRepository
import ru.begunok.data.db.ColdShowerEntity
import ru.begunok.data.db.ReadingEntryEntity
import ru.begunok.data.db.WorkoutEntity
import ru.begunok.health.HealthRepository
import ru.begunok.health.StepHistory
import ru.begunok.lol.LolSettings
import ru.begunok.math.MathActivityEvent
import ru.begunok.workout.Period
import java.time.LocalDate
import java.time.ZoneId

internal data class DailyActivityTableState(
    val rows: List<DailyActivityRow> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val gamesLoading: Boolean = false,
    val gamesMessage: String? = null,
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

private data class GameHistory(
    val games: List<DailyGameActivity>? = null,
    val loading: Boolean = false,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun observeDailyActivity(
    workouts: WorkoutRepository,
    reading: ReadingRepository,
    math: MathRepository,
    showers: ShowerRepository,
    settings: LolSettings,
    health: HealthRepository,
    selection: Flow<ActivityCalendarPeriod>,
    reload: Flow<Int>,
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
                val steps: Flow<StepHistory> = flow {
                    emit(
                        health.dailySteps(
                            start.atStartOfDay(zone).toInstant(),
                            end.plusDays(1).atStartOfDay(zone).toInstant(),
                            zone,
                        ),
                    )
                }
                    .onStart { emit(StepHistory(loading = true)) }
                    .catch { emit(StepHistory(message = it.message ?: "Не удалось прочитать шаги.", failed = true)) }
                    .flowOn(Dispatchers.IO)
                // Token registration must not restart an in-flight match-history request.
                val connection = settings.prefs
                    .map { Triple(it.serverUrl, it.myRiotId, it.demo) }
                    .distinctUntilChanged()
                val games = connection.flatMapLatest { config ->
                    flow {
                        val message = when {
                            config.third -> "Игры LoL: демо-данные не учитываются. Отключите демо-режим в разделе LoL."
                            config.first.isBlank() || config.second.isBlank() ->
                                "Игры LoL: укажите сервер и свой Riot ID в разделе LoL."
                            else -> null
                        }
                        if (message != null) {
                            emit(GameHistory(message = message))
                        } else {
                            emit(GameHistory(loading = true))
                            val history = GameActivityRepository(settings).load(start, end, zone)
                            emit(GameHistory(games = history.games, message = history.message))
                        }
                    }.catch {
                        emit(GameHistory(message = "Не удалось загрузить игры LoL. Остальные активности доступны."))
                    }
                }.onStart { emit(GameHistory(loading = true)) }
                emitAll(
                    combine(local, steps, games) { data, dailySteps, history ->
                        DailyActivityTableState(
                            rows = buildList {
                                val rowsByDay = DailyActivity.build(
                                    workouts = data.workouts,
                                    reading = data.reading,
                                    learning = data.learning,
                                    showers = data.showers,
                                    games = history.games,
                                    steps = dailySteps.days,
                                    today = end,
                                    period = Period.All,
                                    zone = zone,
                                ).filter { it.day >= start && it.day <= end }.associateBy { it.day }
                                var day = end
                                while (day >= start) {
                                    add(
                                        rowsByDay[day] ?: DailyActivityRow(
                                            day = day,
                                            games = history.games?.let { 0 },
                                            gameDurationSec = history.games?.let { 0L },
                                        ),
                                    )
                                    day = day.minusDays(1)
                                }
                            },
                            loading = false,
                            gamesLoading = history.loading,
                            gamesMessage = history.message,
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
