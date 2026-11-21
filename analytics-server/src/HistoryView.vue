<script setup lang="ts">
import type { LineSeriesOption } from 'echarts/charts'
import type { EChartsOption } from 'echarts'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { periods, useResource, type Period, type Series } from './api'
import ChartPlot from './ChartPlot.vue'
import { cursorLine, usePalette, valueAxis } from './charts'
import DateInspect from './DateInspect.vue'
import { buildHistory, isolatedPoints, timeTrace } from './history'
import { DAY, dateLabel, dayStamp, formatValue, isoDate, metricFor, metrics, numberLabel, rangeLabel, shortDateLabel, streams, type Stream } from './metrics'
import RequestState from './RequestState.vue'
import SleepPanel from './SleepPanel.vue'

const route = useRoute()
const router = useRouter()
const stream = computed<Stream>(() => streams.find(value => value === route.query.stream) ?? 'pulse')
const period = computed<Period>(() => periods.find(item => item[0] === route.query.period)?.[0] ?? 'month')
const metric = computed(() => metricFor(stream.value))
const { state, retry } = useResource<Series>(() => `/v1/series?period=${period.value}&stream=${stream.value}`)
const palette = usePalette()
const compare = ref(true)
const picked = ref<string>()
const showRows = ref(false)
watch([stream, period], () => { picked.value = undefined; showRows.value = false })

function navigate(key: 'stream' | 'period', value: string) {
  void router.push({ name: 'history', query: { stream: stream.value, period: period.value, [key]: value } })
}
const model = computed(() => state.value.status === 'ready' ? buildHistory(state.value.data) : null)
const comparison = computed(() => !!(model.value?.aligned.length && compare.value))
const selectedDate = computed(() => {
  const data = model.value
  if (!data?.current.start || !data.current.end) return undefined
  if (picked.value && picked.value >= data.current.start && picked.value <= data.current.end) return picked.value
  return data.current.points.at(-1)?.date ?? data.current.end
})

function priorDate(date: string) {
  const data = model.value
  if (!data?.previous?.start || !data.previous.end) return undefined
  const stamp = dayStamp(date) - data.displacement
  return stamp >= dayStamp(data.previous.start) && stamp <= dayStamp(data.previous.end) ? isoDate(stamp) : undefined
}

function rowFor(date: string) {
  const data = model.value
  const prior = comparison.value ? priorDate(date) : undefined
  return { date, current: data?.current.lookup.get(date), prior, previous: prior ? data?.previous?.lookup.get(prior) : undefined }
}
const selection = computed(() => selectedDate.value ? rowFor(selectedDate.value) : undefined)
const rows = computed(() => {
  const data = model.value
  if (!showRows.value || !data) return []
  const dates = new Set(data.current.lookup.keys())
  if (comparison.value) for (const point of data.aligned) dates.add(point.date)
  return [...dates].sort().reverse().map(rowFor)
})
const delta = computed(() => {
  const current = model.value?.current.mean
  const previous = model.value?.previous?.mean
  if (current == null || previous == null) return ''
  const change = formatValue(stream.value, Math.abs(current - previous))
  return change === formatValue(stream.value, 0)
    ? 'Без изменения' : `${current < previous ? '−' : '+'}${change}`
})

const option = computed<EChartsOption | undefined>(() => {
  const data = model.value
  const date = selectedDate.value
  if (!data?.current.start || !data.current.end || !date || !(data.current.points.length || comparison.value)) return
  const colors = palette.value
  const currentColor = stream.value === 'sleep' ? colors.sleep : stream.value === 'stress' ? colors.stress : colors.current
  const scale = metric.value.scale ?? 1
  const start = dayStamp(data.current.start)
  const end = dayStamp(data.current.end)
  const series: LineSeriesOption[] = []
  for (const { points, isPrior, color } of [
    { points: comparison.value ? data.aligned : [], isPrior: true, color: colors.previous },
    { points: data.current.points, isPrior: false, color: currentColor },
  ]) {
    const dense = points.length > 140
    if (!points.length && isPrior) continue
    series.push({
      type: 'line', data: timeTrace(points, scale), silent: true, showSymbol: !dense, symbol: 'circle', symbolSize: 4.5,
      itemStyle: { color }, lineStyle: { color, width: 2, type: isPrior ? 'dashed' : 'solid' },
      markLine: isPrior ? undefined : cursorLine(dayStamp(date), colors),
    })
    if (dense) series.push({
      type: 'line', data: timeTrace(isolatedPoints(points), scale), silent: true, symbol: 'circle', symbolSize: 3.5,
      itemStyle: { color }, lineStyle: { opacity: 0 },
    })
  }
  return {
    animation: false, useUTC: true,
    grid: { top: 12, bottom: 28, left: 54, right: 20 },
    xAxis: {
      type: 'time', min: start === end ? start - DAY / 2 : start, max: start === end ? end + DAY / 2 : end,
      minInterval: DAY, splitNumber: 6, axisTick: { show: false }, axisLine: { lineStyle: { color: colors.grid } },
      axisLabel: { color: colors.text, fontSize: 10, hideOverlap: true, formatter: (stamp: number) => end - start >= 365 * DAY ? dateLabel(isoDate(stamp)) : shortDateLabel(isoDate(stamp)) },
      splitLine: { show: false },
    },
    yAxis: valueAxis(colors, 0, metric.value.max ? { min: 0, max: metric.value.max } : { scale: !metric.value.total }),
    series,
  }
})

function pickPoint(stamp: number) {
  const data = model.value
  if (!data?.current.start || !data.current.end) return
  const rounded = Math.round(stamp / DAY) * DAY
  picked.value = isoDate(Math.max(dayStamp(data.current.start), Math.min(rounded, dayStamp(data.current.end))))
}
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>История</h1>
      <p>Показатели по дням и сравнение с предыдущим периодом.</p>
    </div>
  </div>
  <div class="history-workspace">
    <aside class="metric-index" aria-label="Выбор показателя">
      <h2 class="group-title">Показатели</h2>
      <div class="metric-menu" role="group" aria-label="Показатель">
        <button v-for="key in streams" :key="key" type="button" :aria-pressed="stream === key" @click="navigate('stream', key)">
          <span>{{ metrics[key].name }}</span><small>{{ metrics[key].unit }}</small>
        </button>
      </div>
      <label class="mobile-metric">
        <span class="field-label">Показатель</span>
        <select :value="stream" @change="navigate('stream', ($event.target as HTMLSelectElement).value)">
          <option v-for="key in streams" :key="key" :value="key">{{ metrics[key].name }}</option>
        </select>
      </label>
    </aside>

    <div class="grid min-w-0 content-start gap-4">
      <div class="period-bar">
        <div class="period-buttons" role="group" aria-label="Период">
          <button v-for="[key, label] in periods" :key="key" type="button" :aria-pressed="period === key" @click="navigate('period', key)">{{ label }}</button>
        </div>
        <span v-if="model" class="range-label">{{ rangeLabel(model.current.start, model.current.end) }}</span>
      </div>
      <RequestState v-if="state.status === 'loading'" loading />
      <RequestState v-else-if="state.status === 'error'" :message="state.message" @retry="retry" />
      <template v-else-if="model">
        <section class="panel" aria-labelledby="history-metric-title">
          <div class="panel-heading">
            <div>
              <h2 id="history-metric-title">{{ metric.name }}</h2>
              <p>{{ metric.description }}</p>
            </div>
            <label v-if="model.aligned.length" class="checkbox-label">
              <input v-model="compare" type="checkbox"> Сравнить периоды
            </label>
          </div>
          <dl class="stats">
            <div>
              <dt>Среднее за день</dt><dd>{{ formatValue(stream, model.current.mean) }}</dd>
              <small v-if="metric.total && model.current.points.length">Всего: {{ formatValue(stream, model.current.sum) }}</small>
            </div>
            <div v-if="model.previous">
              <dt>Предыдущий период</dt><dd>{{ formatValue(stream, model.previous.mean) }}</dd>
              <small>{{ rangeLabel(model.previous.start, model.previous.end) }}<template v-if="delta"> · {{ delta }}</template></small>
            </div>
            <div v-else><dt>Записей за всё время</dt><dd>{{ numberLabel(model.current.count) }}</dd></div>
            <div>
              <dt>Дней с данными</dt>
              <dd>{{ numberLabel(model.current.points.length) }}<template v-if="model.current.start && model.current.end"> / {{ numberLabel((dayStamp(model.current.end) - dayStamp(model.current.start)) / DAY + 1) }}</template></dd>
              <small v-if="model.current.points.length">Последние: {{ dateLabel(model.current.points.at(-1)!.date) }}</small>
            </div>
          </dl>
          <p v-if="!option" class="empty-notice">За выбранный период нет записей для этого показателя.</p>
          <template v-else>
            <figure class="history-chart" :data-stream="stream">
              <figcaption>
                <span>{{ metric.unit }}</span>
                <span class="chart-legend"><span><i class="key-current" aria-hidden="true"></i>Выбранный</span><span v-if="comparison"><i class="key-previous" aria-hidden="true"></i>Предыдущий</span></span>
              </figcaption>
              <ChartPlot class="history-plot" :option="option" :label="`${metric.name} по дням. Значения доступны в таблице.`" :grid-count="1" @pick="pickPoint" />
            </figure>
            <div v-if="selection && model.current.start && model.current.end" class="inspection">
              <DateInspect :date="selection.date" :min="model.current.start" :max="model.current.end" label="Дата на графике" @change="picked = $event" />
              <dl class="comparison-readings" aria-live="polite">
                <div><dt>Выбранный · {{ shortDateLabel(selection.date) }}</dt><dd>{{ formatValue(stream, selection.current?.value) }}</dd></div>
                <div v-if="selection.prior"><dt>Предыдущий · {{ shortDateLabel(selection.prior) }}</dt><dd>{{ formatValue(stream, selection.previous?.value) }}</dd></div>
                <div v-if="model.lifetimeMean !== null && stream !== 'sleep'"><dt>Среднее за всё время</dt><dd>{{ formatValue(stream, model.lifetimeMean) }}</dd></div>
              </dl>
            </div>
            <p class="panel-note">Разрывы — дни без записей, а не нулевые значения.<template v-if="comparison"> Дни совмещены относительно конца каждого периода.</template></p>
            <details :key="`${stream}-${period}`" class="data-details" @toggle="showRows = ($event.target as HTMLDetailsElement).open">
              <summary>Значения по дням</summary>
              <div v-if="showRows" class="table-scroll" tabindex="0" role="region" aria-label="Значения по дням">
                <table>
                  <thead><tr><th scope="col">Дата</th><th scope="col">Значение</th><th scope="col">Записей</th><th v-if="comparison" scope="col">Пред. дата</th><th v-if="comparison" scope="col">Пред. значение</th></tr></thead>
                  <tbody>
                    <tr v-for="row in rows" :key="row.date" :class="{ 'chosen-row': row.date === selectedDate }">
                      <th scope="row"><time :datetime="row.date">{{ dateLabel(row.date) }}</time></th>
                      <td>{{ formatValue(stream, row.current?.value) }}</td><td>{{ numberLabel(row.current?.count ?? 0) }}</td>
                      <td v-if="comparison">{{ row.prior ? dateLabel(row.prior) : '—' }}</td>
                      <td v-if="comparison">{{ formatValue(stream, row.previous?.value) }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </details>
          </template>
        </section>
        <SleepPanel v-if="stream === 'sleep'" :selected-date="selectedDate" :dates="model.current.points.map(point => point.date)" @change="picked = $event" />
      </template>
    </div>
  </div>
</template>
