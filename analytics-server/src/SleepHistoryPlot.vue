<script setup lang="ts">
import type { EChartsOption } from 'echarts'
import type { BarSeriesOption } from 'echarts/charts'
import { computed } from 'vue'
import type { PhaseSeconds } from './api'
import ChartPlot from './ChartPlot.vue'
import { usePalette, valueAxis } from './charts'
import { durationLabel, shortDateLabel } from './metrics'
import { comparedPhases, phaseIndex, phases } from './sleep'

const props = defineProps<{ records: {date: string; phases?: PhaseSeconds}[]; selected?: string; compact?: boolean }>()
const emit = defineEmits<{ pick: [day: string] }>()
const colors = usePalette()
const hasPhases = computed(() => props.records.some(r => comparedPhases.some(k => r.phases?.[k] != null)))
function select(index: number) {
  const night = props.records[Math.round(index)]
  if (night) emit('pick', night.date)
}
const option = computed<EChartsOption>(() => ({
  animation: false,
  grid: { left: 40, right: 12, top: 8, bottom: 25 },
  tooltip: { trigger: 'axis', renderMode: 'richText', confine: true, valueFormatter: v => typeof v === 'number' ? durationLabel(v * 60, true) : '—' },
  xAxis: { type: 'category', data: props.records.map(r => r.date), axisLabel: { color: colors.value.text, hideOverlap: true, formatter: shortDateLabel } },
  yAxis: valueAxis(colors.value, 0, { min: 0 }),
  series: comparedPhases.map<BarSeriesOption>((key, i) => ({
    type: 'bar', name: phases[phaseIndex(key)][1], stack: 'night', barMaxWidth: 30,
    itemStyle: { color: colors.value.stages[phaseIndex(key)] },
    markLine: !i && props.selected ? { silent: true, symbol: 'none', label: { show: false }, lineStyle: { color: colors.value.cursor, type: 'dotted' }, data: [{ xAxis: props.selected }] } : undefined,
    data: props.records.map(r => { const s = r.phases?.[key]; return s == null ? null : s / 60 }),
  })),
}))
</script>

<template>
  <figure v-if="hasPhases" class="overview-figure">
    <figcaption>Длительность фаз · мин · ночи с записями</figcaption>
    <ChartPlot :class="compact ? 'overview-plot' : 'history-plot'" :option="option" :grid-count="compact ? 0 : 1" label="Длительность фаз по ночам" @pick="select" />
    <ul class="sleep-legend"><li v-for="key in comparedPhases" :key="key"><svg aria-hidden="true" width="11" height="4"><path d="M0 2h11" :stroke="colors.stages[phaseIndex(key)]" stroke-width="3" /></svg>{{ phases[phaseIndex(key)][1] }}</li></ul>
  </figure>
  <p v-else class="overview-empty">{{ records.length ? 'Записи есть, но данных о фазах за этот период нет.' : 'За этот период записей о сне нет.' }}</p>
</template>
