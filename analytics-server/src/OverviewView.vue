<script setup lang="ts">
import type { EChartsOption } from 'echarts'
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { useResource, type Overview } from './api'
import ChartPlot from './ChartPlot.vue'
import { usePalette } from './charts'
import { dateLabel, formatValue, localToday, metricFor, metricGroups, numberLabel, rangeLabel, recentDates, shortDateLabel, timeLabel } from './metrics'
import { overviewInsights } from './overviewInsights'
import RequestState from './RequestState.vue'
import SleepHistoryPlot from './SleepHistoryPlot.vue'
import SleepComparison from './SleepComparison.vue'

const { state, retry } = useResource<Overview>(() => '/v1/overview')
const palette = usePalette()
const dates = computed(() => {
  // Re-evaluate the local window when the server supplies a fresh snapshot.
  return state.value.status === 'ready' ? recentDates(localToday(), 30) : []
})
const groups = computed(() => {
  if (state.value.status !== 'ready') return []
  const { series } = state.value.data
  return metricGroups.map(group => ({
    name: group.name,
    cards: group.streams.map(stream => {
      const { latest, days } = series[stream]
      const metric = metricFor(stream)
      const bars = metric.total === true
      const colors = palette.value
      const color = stream === 'sleep' ? colors.sleep : stream === 'stress' ? colors.stress : bars ? colors.previous : colors.current
      const scale = stream === 'sleep' ? 3600 : metric.scale ?? 1
      const unit = stream === 'sleep' ? 'ч' : metric.unit
      const points = new Map(days.map(point => [point.date, point.value / scale]))
      // Use the observed span within the 30-day window; keep gaps between observations.
      const observed = days.length > 1 ? dates.value.filter(day => day >= days[0].date && day <= days[days.length - 1].date) : days.map(point => point.date)
      const data = observed.map(day => points.get(day) ?? null)
      const today = dates.value.at(-1) ?? localToday()
      const option: EChartsOption = {
        animation: false,
        grid: { top: 12, right: 14, bottom: 10, left: 4, outerBoundsMode: 'same', outerBoundsContain: 'axisLabel' },
        tooltip: {
          trigger: 'axis', renderMode: 'richText', confine: true, backgroundColor: '#202833', borderWidth: 0,
          textStyle: { color: '#f1f5fa', fontSize: 14 },
          axisPointer: { type: bars ? 'shadow' : 'line' },
          valueFormatter: value => typeof value === 'number' ? formatValue(stream, value * scale) : 'Нет данных',
        },
        xAxis: {
          type: 'category', data: observed, boundaryGap: bars || observed.length === 1, axisTick: { show: false },
          axisLabel: { color: colors.text, fontSize: 13, margin: 11, hideOverlap: true, formatter: shortDateLabel },
          axisLine: { lineStyle: { color: colors.grid } },
        },
        yAxis: {
          type: 'value', splitNumber: 4, scale: !bars, max: metric.max,
          axisLabel: { color: colors.text, fontSize: 13, hideOverlap: true, formatter: (value: number) => numberLabel(value, 2) },
          splitLine: { lineStyle: { color: colors.grid, type: 'dashed' } },
        },
        series: bars ? [{
          name: metric.name, type: 'bar', data, barMaxWidth: 38,
          itemStyle: { color, borderRadius: [5, 5, 0, 0] },
        }] : [{
          name: metric.name, type: 'line', data, connectNulls: false,
          showSymbol: true, symbol: 'circle', symbolSize: 6,
          lineStyle: { color, width: 2.5 }, itemStyle: { color },
          areaStyle: { color, opacity: 0.09 },
        }],
      }
      return {
        stream, name: metric.name, latest, time: !bars ? timeLabel(latest?.time) : undefined,
        days, unit, option, insights: stream === 'sleep' ? [] : overviewInsights(stream, days, today),
        partial: bars && stream !== 'sleep' && latest?.date === today,
        aggregation: bars ? 'Итог за день' : 'Среднее за день',
        latestLabel: stream === 'sleep' ? 'Последняя основная ночь' : bars ? 'Последний дневной итог' : 'Последний замер',
      }
    }),
  }))
})
const hasData = computed(() => groups.value.some(group => group.cards.some(card => card.latest)))
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>Обзор</h1>
      <p>Последние значения и динамика за 30 дней.</p>
    </div>
    <span v-if="dates.length" class="range-label">{{ rangeLabel(dates[0], dates.at(-1) ?? null) }}</span>
  </div>

  <RequestState v-if="state.status === 'loading'" loading />
  <RequestState v-else-if="state.status === 'error'" :message="state.message" @retry="retry" />
  <template v-else-if="state.status === 'ready'">
    <p v-if="!hasData" class="empty-notice">Пока нет показателей. Подключите телефон кабелем, разрешите USB-отладку и нажмите «Синхронизировать по USB».</p>
    <div class="grid gap-9">
      <section v-for="(group, index) in groups" :key="group.name" :aria-labelledby="`overview-group-${index}`">
        <h2 :id="`overview-group-${index}`" class="group-title">{{ group.name }}</h2>
        <div class="metric-grid">
          <article v-for="card in group.cards" :key="card.stream" class="metric-card">
            <div class="metric-card-head">
              <div>
                <h3 class="metric-card-label">{{ card.name }}</h3>
                <div class="metric-card-value">{{ formatValue(card.stream, card.latest?.value) }}</div>
                <p v-if="card.latest" class="metric-card-date">
                  {{ card.latestLabel }} · <time :datetime="card.latest.date">{{ dateLabel(card.latest.date) }}</time><template v-if="card.time"> · {{ card.time }}</template>
                </p>
                <p v-else class="metric-card-date">Нет записей</p>
              </div>
              <RouterLink class="metric-card-link" :aria-label="`История · ${card.name}`" :to="{ name: 'history', query: { stream: card.stream } }">
                История <span aria-hidden="true">↗</span>
              </RouterLink>
            </div>
            <SleepHistoryPlot v-if="card.stream === 'sleep'" :records="card.days" compact />
            <figure v-else-if="card.days.length" class="overview-figure">
              <figcaption>{{ card.aggregation }} · {{ card.unit }}</figcaption>
              <ChartPlot class="overview-plot" :option="card.option" :label="`${card.name}: ${card.aggregation.toLowerCase()} за 30 дней, ${card.unit}`" />
            </figure>
            <p v-else class="overview-empty">Нет данных на графике за последние 30 дней</p>
            <template v-if="card.stream === 'sleep'">
              <SleepComparison v-if="state.data.series.sleep.latest && state.data.series.sleep.comparison" :night="state.data.series.sleep.latest" :comparison="state.data.series.sleep.comparison" />
            </template>
            <section v-else class="metric-insights" :aria-label="`${card.name}: сравнение с прошлым`">
              <h4>Что изменилось</h4>
              <p v-if="card.partial" class="insight-note">Сегодняшний итог ещё неполный и может измениться.</p>
              <dl v-if="card.insights.length" class="insight-grid">
                <div v-for="insight in card.insights" :key="insight.label">
                  <dt>{{ insight.label }}</dt>
                  <dd>{{ insight.detail }}</dd>
                </div>
              </dl>
              <p v-else class="insight-note">{{ card.days.length ? 'Нужен ещё хотя бы один день с данными для сравнения.' : 'За последние 30 дней нет данных для сравнения.' }}</p>
            </section>
          </article>
        </div>
      </section>
    </div>
  </template>
</template>
