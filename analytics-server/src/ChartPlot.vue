<script lang="ts">
import { BarChart, LineChart } from 'echarts/charts'
import { AxisPointerComponent, GridComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import { use } from 'echarts/core'
import { SVGRenderer } from 'echarts/renderers'

use([SVGRenderer, GridComponent, AxisPointerComponent, TooltipComponent, MarkLineComponent, LineChart, BarChart])
</script>

<script setup lang="ts">
import { init, type EChartsType } from 'echarts/core'
import type { EChartsOption } from 'echarts'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps<{ option: EChartsOption; label?: string; gridCount?: number }>()
const emit = defineEmits<{ pick: [value: number] }>()
const host = ref<HTMLDivElement>()
let chart: EChartsType | undefined
let observer: ResizeObserver | undefined

onMounted(() => {
  if (!host.value) return
  const instance = init(host.value, undefined, { renderer: 'svg' })
  chart = instance
  instance.setOption(props.option)
  instance.getZr().on('click', event => {
    const { offsetX, offsetY } = event
    for (let gridIndex = 0; gridIndex < (props.gridCount ?? 0); gridIndex++) {
      if (!instance.containPixel({ gridIndex }, [offsetX, offsetY])) continue
      const value = instance.convertFromPixel({ xAxisIndex: gridIndex }, offsetX)
      if (typeof value === 'number' && Number.isFinite(value)) emit('pick', value)
      break
    }
  })
  observer = new ResizeObserver(() => instance.resize())
  observer.observe(host.value)
})

watch(() => props.option, option => chart?.setOption(option, { notMerge: true }))
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <div ref="host" class="chart" :class="{ 'chart-pickable': gridCount }" :role="label ? 'img' : undefined" :aria-label="label" :aria-hidden="!label || undefined"></div>
</template>
