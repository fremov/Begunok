import type { LineSeriesOption } from 'echarts/charts'
import { onMounted, onUnmounted, shallowRef } from 'vue'

const dark = {
  text: '#abb5c4', grid: '#2b3440', cursor: '#b0bed0',
  current: '#ffa380', previous: '#92aecf', sleep: '#a5b7ff', stress: '#ffae79',
  stages: ['#edc6b4', '#ff9c76', '#d2dc79', '#83bdff', '#a899fa', '#b4bfcd', '#9aa4b2'],
}
const light: typeof dark = {
  text: '#505e70', grid: '#dce1e9', cursor: '#596c85',
  current: '#a23b17', previous: '#4d6c91', sleep: '#514eb0', stress: '#a24117',
  stages: ['#813710', '#a83e15', '#586b07', '#226bb7', '#5f44ab', '#536171', '#606b7c'],
}
export type Palette = typeof dark

export function usePalette() {
  const query = window.matchMedia('(prefers-color-scheme: light)')
  const palette = shallowRef<Palette>(query.matches ? light : dark)
  const update = (event: MediaQueryListEvent) => { palette.value = event.matches ? light : dark }
  onMounted(() => query.addEventListener('change', update))
  onUnmounted(() => query.removeEventListener('change', update))
  return palette
}

export function valueAxis(palette: Palette, gridIndex = 0, range: { min?: number; max?: number; scale?: boolean } = {}) {
  return {
    type: 'value' as const, gridIndex, splitNumber: 3, ...range,
    axisLabel: { color: palette.text, fontSize: 13, hideOverlap: true },
    splitLine: { lineStyle: { color: palette.grid } },
  }
}

export function cursorLine(x: string | number, palette: Palette): LineSeriesOption['markLine'] {
  return {
    silent: true, symbol: 'none', label: { show: false },
    lineStyle: { color: palette.cursor, type: 'dotted', width: 1 },
    data: [{ xAxis: x }],
  }
}
