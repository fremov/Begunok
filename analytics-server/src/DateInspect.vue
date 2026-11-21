<script setup lang="ts">
import { shiftDate } from './metrics'

const props = defineProps<{ date: string; min: string; max: string; label: string }>()
const emit = defineEmits<{ change: [date: string] }>()

function setDate(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.validity.valid && input.value) emit('change', input.value)
  else input.value = props.date
}
</script>

<template>
  <div class="date-control" role="group" :aria-label="label">
    <button class="button arrow-button" type="button" aria-label="Предыдущий день" :disabled="date <= min" @click="emit('change', shiftDate(date, -1))">‹</button>
    <label>
      <span class="sr-only">{{ label }}</span>
      <input type="date" required :value="date" :min="min" :max="max" @change="setDate">
    </label>
    <button class="button arrow-button" type="button" aria-label="Следующий день" :disabled="date >= max" @click="emit('change', shiftDate(date, 1))">›</button>
  </div>
</template>
