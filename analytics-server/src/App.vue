<script setup lang="ts">
import { computed, onMounted, onUnmounted, provide, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { errorMessage, refreshKey, request } from './api'
import { numberLabel } from './metrics'

const menu = [
  { name: 'overview', label: 'Обзор' },
  { name: 'history', label: 'История' },
  { name: 'calendar', label: 'Календарь активности' },
] as const
const route = useRoute()
const main = ref<HTMLElement>()
const refresh = ref(0)
provide(refreshKey, refresh)

watch(() => route.name, (name, previous) => {
  const page = menu.find(item => item.name === name)
  document.title = `${page?.label ?? 'Аналитика'} · MyLife`
  if (previous) main.value?.focus({ preventScroll: true })
}, { flush: 'post', immediate: true })

const usb = ref<'checking' | 'ready' | 'busy' | 'success' | 'partial' | 'warning' | 'error'>('checking')
const notice = ref('')
const labels = {
  checking: 'Проверяем службу USB', ready: 'Служба USB готова', busy: 'Синхронизация…',
  success: 'Готово', partial: 'Обновлено частично', warning: 'Есть предупреждение', error: 'Ошибка USB',
}
const status = computed(() => labels[usb.value])
const compactLabels = { checking: 'Проверка', ready: 'Готово', busy: 'Обмен…', success: 'Готово', partial: 'Частично', warning: 'Внимание', error: 'Ошибка' }
const compactStatus = computed(() => compactLabels[usb.value])
const controller = new AbortController()
const hint = 'Подключите телефон кабелем и разрешите USB-отладку.'

onMounted(async () => {
  try {
    const data = await request<{ app: string; error?: string }>('/v1/usb', { signal: controller.signal })
    if (data.app !== 'MyLifeAnalyticsUSB') throw new Error('Служба синхронизации по USB недоступна.')
    if (data.error) throw new Error(data.error)
    usb.value = 'ready'
  } catch (error) {
    if (controller.signal.aborted) return
    usb.value = 'error'
    notice.value = errorMessage(error)
  }
})
onUnmounted(() => controller.abort())

async function syncUsb() {
  usb.value = 'busy'
  notice.value = 'Читаем данные телефона и передаём на него дневные итоги ПК. Не отключайте USB-кабель.'
  try {
    const { count, partial, warning } = await request<{ count: number; partial?: boolean; warning?: string }>('/v1/usb', { method: 'POST' })
    usb.value = partial ? 'partial' : warning ? 'warning' : 'success'
    const result = partial ? 'Синхронизация выполнена частично.' : 'Синхронизация завершена.'
    notice.value = [result, warning, `Записей в снимке показателей: ${numberLabel(count)}.`].filter(Boolean).join(' ')
    refresh.value++
  } catch (error) {
    usb.value = 'error'
    notice.value = errorMessage(error)
  }
}
</script>

<template>
  <a class="skip-link" href="#main-content" @click.prevent="main?.focus()">К содержанию</a>
  <header class="site-header">
    <div class="shell masthead">
      <RouterLink class="brand" :to="{ name: 'overview' }" aria-label="MyLife Аналитика — обзор">
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" width="19" height="19"><path d="M2 13h5l2.5-6L13 18l3.5-7 2 2H22" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" /></svg>
        </span>
        <span>MyLife<span class="brand-product"> Аналитика</span></span>
      </RouterLink>
      <nav class="site-nav" aria-label="Разделы аналитики">
        <RouterLink v-for="item in menu" :key="item.name" :to="{ name: item.name }">{{ item.label }}</RouterLink>
      </nav>
      <div class="usb-tools">
        <span class="usb-status" :data-state="usb">
          <span class="status-dot" aria-hidden="true"></span>
          <span class="usb-full-status">{{ status }}</span><span class="usb-compact-status" aria-hidden="true">{{ compactStatus }}</span>
        </span>
        <button class="button usb-button" type="button" :disabled="usb === 'busy' || usb === 'checking'" :title="hint" @click="syncUsb">
          <svg aria-hidden="true" viewBox="0 0 20 20" fill="none" width="16" height="16"><path d="M3 7h13l-3-3m4 9H4l3 3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" /></svg>
          <span>Синхронизировать<span class="usb-button-qualifier"> по USB</span></span>
        </button>
      </div>
    </div>
  </header>
  <div class="shell" role="status" aria-live="polite">
    <p v-if="notice" class="usb-notice" :data-state="usb">{{ notice }}</p>
  </div>
  <main id="main-content" ref="main" class="shell page" tabindex="-1">
    <RouterView />
  </main>
</template>
