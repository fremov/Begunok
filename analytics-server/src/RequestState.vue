<script setup lang="ts">
defineProps<{ loading?: boolean; message?: string; compact?: boolean }>()
const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div :class="compact ? 'inline-state' : 'panel page-state'">
    <template v-if="loading">
      <p role="status">Загружаем данные…</p>
    </template>
    <template v-else>
      <p role="alert" class="error-text">{{ message }}</p>
      <button type="button" class="button" @click="emit('retry')">Повторить</button>
    </template>
  </div>
</template>
