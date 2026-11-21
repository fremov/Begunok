<script setup lang="ts">
import type { EChartsOption } from 'echarts'
import { computed, ref, watch } from 'vue'
import { useResource, type SleepDetails } from './api'
import ChartPlot from './ChartPlot.vue'
import { usePalette } from './charts'
import { dateLabel, durationLabel } from './metrics'
import RequestState from './RequestState.vue'
import { clock, offsetLabel, phases, sleepAnalysis } from './sleep'

const props = defineProps<{ selectedDate?: string; dates: string[] }>()
const emit = defineEmits<{ change: [date: string] }>()
const palette = usePalette()
const date = computed(() => props.selectedDate && props.dates.includes(props.selectedDate) ? props.selectedDate : null)
const { state, retry } = useResource<SleepDetails>(() => date.value ? `/v1/sleep?date=${date.value}` : null)
const index = ref(0)
watch(date, () => { index.value = 0 })
const sessions = computed(() => state.value.status === 'ready'
  ? state.value.data.records.map(sleepAnalysis).filter(session => session !== null).sort((a, b) => a.start - b.start)
  : [])
const session = computed(() => sessions.value[index.value] ?? sessions.value[0])
const totals = computed(() => {
  const counts = new Map<number, number>()
  for (const { start, end, phase } of session.value?.segments ?? []) counts.set(phase, (counts.get(phase) ?? 0) + (end - start) / 1000)
  return [...counts].sort(([first], [second]) => first - second)
})
const unknown = computed(() => totals.value.length === 1 && totals.value[0][0] === phases.length - 1)
const option = computed<EChartsOption | undefined>(() => {
  const night = session.value
  if (!night) return
  const colors = palette.value
  const data = phases.map((): [number, string | null][] => [])
  const joins: [number, string | null][] = []
  const names = totals.value.map(([phase]) => phases[phase][1])
  for (let i = 0; i < night.segments.length; i++) {
    const { start, end, phase } = night.segments[i]
    const name = phases[phase][1]
    const prior = night.segments[i - 1]
    data[phase].push([start, name], [end, name], [end, null])
    if (prior && prior.phase < phases.length - 1 && phase < phases.length - 1) {
      joins.push([start, phases[prior.phase][1]], [start, name], [start, null])
    }
  }
  return {
    animation: false,
    grid: { top: 9, right: 19, bottom: 24, left: 107 },
    xAxis: {
      type: 'value', min: night.start, max: night.end, splitNumber: 5,
      axisLine: { show: true, lineStyle: { color: colors.grid } }, axisTick: { show: false },
      axisLabel: { color: colors.text, fontSize: 10, hideOverlap: true, formatter: (stamp: number) => clock(stamp, night.offset) },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'category', inverse: true, data: names,
      axisLine: { show: false }, axisTick: { show: false }, axisLabel: { interval: 0, color: colors.text, fontSize: 10 },
      splitLine: { show: true, lineStyle: { color: colors.grid } },
    },
    series: [
      { type: 'line', data: joins, encode: { x: 0, y: 1 }, showSymbol: false, silent: true, lineStyle: { color: colors.cursor, width: 1 } },
      ...data.flatMap((points, phase) => points.length ? [{
        type: 'line' as const, data: points, encode: { x: 0, y: 1 }, showSymbol: false, silent: true,
        lineStyle: { color: colors.stages[phase], width: 4, type: phase === phases.length - 1 ? 'dashed' as const : 'solid' as const },
      }] : []),
    ],
  }
})
const comparison = computed(() => state.value.status === 'ready' ? state.value.data.comparison : null)
const minutes = (value: number | null) => value === null ? '—' : durationLabel(value * 60)
</script>

<template>
  <section class="panel" aria-labelledby="sleep-title">
    <div class="panel-heading">
      <div>
        <h2 id="sleep-title">Фазы сна</h2>
        <p>Гипнограмма по сеансам. Выберите дату пробуждения.</p>
      </div>
      <label v-if="dates.length" class="sleep-control">
        <span class="field-label">Ночи с записями</span>
        <select :value="date ?? ''" @change="emit('change', ($event.target as HTMLSelectElement).value)">
          <option value="" disabled>Нет записи на дату</option>
          <option v-for="day in [...dates].reverse()" :key="day" :value="day">{{ dateLabel(day) }}</option>
        </select>
      </label>
    </div>
    <p v-if="!date" class="empty-notice">{{ selectedDate ? `Нет записи о сне за ${dateLabel(selectedDate)}.` : 'Нет записей о сне за этот период.' }}</p>
    <RequestState v-else-if="state.status === 'loading'" loading compact />
    <RequestState v-else-if="state.status === 'error'" :message="state.message" compact @retry="retry" />
    <p v-else-if="state.status === 'ready' && !session" class="empty-notice">Нет сохранённых сеансов за эту дату.</p>
    <template v-else-if="date && session && option">
      <div class="sleep-session">
        <p>{{ clock(session.start, session.offset) }}–{{ clock(session.end, session.offset) }} ({{ offsetLabel(session.offset) }}) · сна {{ durationLabel(session.seconds) }}</p>
        <label v-if="sessions.length > 1" class="sleep-control">
          <span class="field-label">Сеанс</span>
          <select v-model="index">
            <option v-for="(item, i) in sessions" :key="item.id" :value="i">{{ clock(item.start, item.offset) }}–{{ clock(item.end, item.offset) }}</option>
          </select>
        </label>
      </div>
      <p v-if="unknown" class="panel-note">Длительность сеанса сохранена, но однозначных данных о фазах нет.</p>
      <ChartPlot class="hypnogram" :class="{ 'hypnogram-short': totals.length < 4 }" :option="option" :label="`Фазы сна за ${dateLabel(date)}. Интервалы доступны в таблице.`" />
      <ul class="sleep-legend" aria-label="Длительность по фазам">
        <li v-for="[phase, value] in totals" :key="phase">
          <svg aria-hidden="true" width="11" height="4"><path d="M0 2h11" :stroke="palette.stages[phase]" stroke-width="3" :stroke-dasharray="phase === phases.length - 1 ? '3 2' : undefined" /></svg>
          {{ phases[phase][1] }} <strong>{{ durationLabel(value, true) }}</strong>
        </li>
      </ul>
      <dl v-if="comparison" class="stats" aria-label="Сравнение ночей">
        <div><dt>Выбранная ночь</dt><dd>{{ minutes(comparison.minutes) }}</dd></div>
        <div><dt>Предыдущая ночь</dt><dd>{{ minutes(comparison.previous_minutes) }}</dd><small v-if="comparison.previous_date">{{ dateLabel(comparison.previous_date) }}</small></div>
        <div><dt>Среднее по более ранним ночам</dt><dd>{{ minutes(comparison.earlier_mean_minutes) }}</dd></div>
      </dl>
      <details class="data-details">
        <summary>Интервалы по фазам</summary>
        <div class="table-scroll" tabindex="0" role="region" aria-label="Интервалы по фазам сна">
          <table>
            <thead><tr><th scope="col">Начало</th><th scope="col">Конец</th><th scope="col">Фаза</th><th scope="col">Длительность</th></tr></thead>
            <tbody>
              <tr v-for="segment in session.segments" :key="segment.start">
                <th scope="row">{{ clock(segment.start, session.offset) }}</th><td>{{ clock(segment.end, session.offset) }}</td>
                <td>{{ phases[segment.phase][1] }}</td><td>{{ durationLabel((segment.end - segment.start) / 1000, true) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </details>
    </template>
  </section>
</template>
