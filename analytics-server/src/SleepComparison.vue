<script setup lang="ts">
import type { PhaseComparison, SleepNight } from './api'
import { dateLabel, durationLabel } from './metrics'
import { comparedPhases, phaseIndex, phases } from './sleep'

defineProps<{ night: SleepNight; comparison: PhaseComparison }>()
const duration = (seconds: number | null) => seconds === null ? '—' : durationLabel(seconds, true)
const reference = (current: number | null, base: number | null) => {
  if (current === null || base === null) return duration(base)
  const delta = Math.round((current - base) / 60)
  return `${duration(base)} (${delta > 0 ? '+' : ''}${delta} мин)`
}
</script>

<template>
  <section class="metric-insights" aria-label="Основной сеанс ночи">
    <h4>Оценка MyLife · {{ night.score === null ? 'нет оценки' : `${night.score} / 100` }}</h4>
    <p v-if="night.score === null" class="insight-note">Для оценки нужны фазы без пробелов и противоречий и хотя бы одна фаза лёгкого, глубокого или REM-сна.</p>
    <details class="data-details">
      <summary>Как считается оценка</summary>
      <p class="panel-note">Эвристика MyLife, не оценка Xiaomi и не медицинский показатель. Длительность — до 50 баллов за 8 часов сна; эффективность — до 30 за долю сна в сеансе; непрерывность — 20 минус 4 за каждое внутреннее пробуждение, не меньше нуля. Итог округляется до целого.</p>
    </details>
    <div class="table-scroll" tabindex="0" role="region" aria-label="Сравнение длительности фаз">
      <table>
        <thead><tr><th scope="col">Фаза</th><th scope="col">Ночь<br>{{ dateLabel(night.date) }}</th><th scope="col">Предыдущая<br>{{ comparison.previous_date ? dateLabel(comparison.previous_date) : '—' }}</th><th scope="col">Среднее<br>за 7 дней</th></tr></thead>
        <tbody><tr v-for="key in comparedPhases" :key="key"><th scope="row">{{ phases[phaseIndex(key)][1] }}</th><td>{{ duration(night.phases[key]) }}</td><td>{{ reference(night.phases[key], comparison.phases[key].previous) }}</td><td>{{ reference(night.phases[key], comparison.phases[key].week_mean) }}</td></tr></tbody>
      </table>
    </div>
    <p class="panel-note">В скобках — насколько выбранная ночь длиннее (+) или короче (−) по этой фазе. Прочерк означает, что данных нет. Среднее учитывает только ночи с этой фазой за предыдущие 7 календарных дней.</p>
  </section>
</template>
