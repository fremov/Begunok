<script setup lang="ts">
import { computed, onMounted, onUnmounted, provide, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { errorMessage, refreshKey, request } from './api'
import { numberLabel } from './metrics'

const menu = [
  { name: 'overview', label: 'Обзор' },
  { name: 'history', label: 'История' },
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

const usb = ref<'checking' | 'ready' | 'busy' | 'success' | 'error'>('checking')
const notice = ref('')
const labels = {
  checking: 'Проверяем USB', ready: 'Импорт доступен', busy: 'Импортируем…',
  success: 'Импорт завершён', error: 'Ошибка импорта',
}
const status = computed(() => labels[usb.value])
const compactLabels = { checking: 'Проверка', ready: 'Доступен', busy: 'Импорт…', success: 'Готово', error: 'Ошибка' }
const compactStatus = computed(() => compactLabels[usb.value])
const controller = new AbortController()
const hint = 'Подключите телефон кабелем и разрешите USB-отладку.'

onMounted(async () => {
  try {
    const data = await request<{ app: string }>('/v1/usb', { signal: controller.signal })
    if (data.app !== 'MyLifeAnalyticsUSB') throw new Error('Служба импорта по USB недоступна.')
    usb.value = 'ready'
  } catch (error) {
    if (controller.signal.aborted) return
    usb.value = 'error'
    notice.value = errorMessage(error)
  }
})
onUnmounted(() => controller.abort())

async function importUsb() {
  usb.value = 'busy'
  notice.value = 'Читаем историю с телефона. Не отключайте USB-кабель.'
  try {
    const { count } = await request<{ count: number }>('/v1/usb', { method: 'POST' })
    usb.value = 'success'
    notice.value = count ? `Импорт завершён. Записей в снимке: ${numberLabel(count)}.` : 'На телефоне пока нет показателей.'
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
        <button class="button usb-button" type="button" :disabled="usb === 'busy' || usb === 'checking'" :title="hint" @click="importUsb">
          <svg aria-hidden="true" viewBox="0 0 20 20" fill="none" width="16" height="16"><path d="M10 3v9m-3-3 3 3 3-3M4 12v4h12v-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" /></svg>
          <span>Импорт<span class="usb-button-preposition"> по</span> USB</span>
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
