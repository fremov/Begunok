<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { useResource } from './api'
import { dateLabel, dayStamp, durationLabel, isoDate, localToday, numberLabel, shiftDate } from './metrics'
import RequestState from './RequestState.vue'

const workoutKinds = [
  ['run', 'Бег'], ['strength_upper', 'Силовая · Верх тела'], ['strength_lower', 'Силовая · Низ тела'],
  ['strength', 'Силовая'], ['stretching', 'Растяжка'], ['other', 'Другая тренировка'],
] as const
type WorkoutKind = typeof workoutKinds[number][0]
interface PhoneDay {
  workouts: number; topics: number; tasks: number; reviews: number
  pages: number; showerDurationSec: number; steps: number | null
  workoutKinds?: Partial<Record<WorkoutKind, number>>
  upperReps?: number; lowerReps?: number
}
const groups = [
  ['ai', 'Нейросети'], ['study', 'Обучение'], ['development', 'Разработка'],
  ['games', 'Игры'], ['leisure', 'Досуг'], ['other', 'Без группы'],
] as const
type Group = typeof groups[number][0]
interface ActivityItem { name: string; seconds: number }
interface CalendarDay {
  phone: PhoneDay | null
  pc: Partial<Record<Group, number>> | null
  details: Partial<Record<Group, ActivityItem[]>> | null
}

function workoutDetails(phone?: PhoneDay | null) {
  return workoutKinds.flatMap(([key, label]) => {
    const count = phone?.workoutKinds?.[key] ?? 0
    return count > 0 ? [{ key, label, count }] : []
  })
}
const mathTotal = (phone: PhoneDay) => phone.topics + phone.tasks + phone.reviews
const activities = [
  { key: 'training', label: 'Тренировки', active: ({ phone }) => (phone?.workouts ?? 0) > 0 },
  { key: 'math', label: 'Математика', active: ({ phone }) => !!phone && mathTotal(phone) > 0 },
  { key: 'reading', label: 'Чтение', active: ({ phone }) => (phone?.pages ?? 0) > 0 },
  { key: 'shower', label: 'Душ', active: ({ phone }) => (phone?.showerDurationSec ?? 0) > 0 },
  { key: 'steps', label: 'Шаги', active: ({ phone }) => (phone?.steps ?? 0) > 0 },
  { key: 'pc', label: 'ПК', active: ({ pc }) => groups.some(([key]) => (pc?.[key] ?? 0) > 0) },
] satisfies { key: string; label: string; active: (day: CalendarDay) => boolean }[]

const today = localToday()
const selected = ref(today)
const view = ref<'month' | 'year'>('month')
const period = computed(() => selected.value.slice(0, view.value === 'year' ? 4 : 7))
const { state, retry } = useResource<{ days: Record<string, CalendarDay> }>(() => `/v1/calendar?period=${period.value}`)
const days = computed<Record<string, CalendarDay>>(() => state.value.status === 'ready' ? state.value.data.days : {})
const currentDay = computed(() => days.value[selected.value])
const phone = computed(() => currentDay.value?.phone)
const pcGroups = computed(() => {
  const day = currentDay.value
  if (day?.pc == null) return []
  const totals = day.pc
  return groups.map(([key, label]) => {
    const seconds = totals[key] ?? 0
    const items = day.details?.[key]
    return {
      key, label, seconds,
      items: items ? [...items].sort((a, b) => b.seconds - a.seconds || a.name.localeCompare(b.name, 'ru'))
        : day.details == null || seconds > 0 ? null : [],
    }
  }).sort((a, b) => b.seconds - a.seconds)
})
const pcTotal = computed(() => pcGroups.value.reduce((total, group) => total + group.seconds, 0))

const weekdays = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс']
const monthName = new Intl.DateTimeFormat('ru-RU', { month: 'long', timeZone: 'UTC' })
const monthYear = new Intl.DateTimeFormat('ru-RU', { month: 'long', year: 'numeric', timeZone: 'UTC' })
function monthLength(first: Date) {
  const last = new Date(first)
  last.setUTCMonth(last.getUTCMonth() + 1, 0)
  return last.getUTCDate()
}
const months = computed(() => {
  const yearly = view.value === 'year'
  return Array.from({ length: yearly ? 12 : 1 }, (_, index) => {
    const prefix = yearly ? `${period.value}-${String(index + 1).padStart(2, '0')}` : period.value
    const first = new Date(dayStamp(`${prefix}-01`))
    return {
      prefix, label: (yearly ? monthName : monthYear).format(first), firstColumn: (first.getUTCDay() + 6) % 7 + 1,
      dates: Array.from({ length: monthLength(first) }, (_, i) => {
        const date = `${prefix}-${String(i + 1).padStart(2, '0')}`
        const day = days.value[date]
        const observed = day?.phone != null || day?.pc != null
        const marks = day ? activities.filter(activity => activity.active(day)) : []
        const description = date > today ? 'будущий день' : !observed ? 'нет данных'
          : marks.length ? marks.map(activity => activity.key === 'training' && day.phone?.workoutKinds
            ? workoutDetails(day.phone).map(item => item.label).join(', ') : activity.label).join(', ') : 'нет отмеченной активности'
        return { date, marks, observed, label: `${dateLabel(date)}: ${description}` }
      }),
    }
  })
})

function movePeriod(direction: number) {
  const date = new Date(dayStamp(selected.value)), day = date.getUTCDate()
  date.setUTCMonth(date.getUTCMonth() + direction * (view.value === 'year' ? 12 : 1), 1)
  date.setUTCDate(Math.min(day, monthLength(date)))
  const moved = isoDate(date.getTime())
  selected.value = moved > today ? today : moved
}
function pickDate(event: Event) {
  const input = event.target as HTMLInputElement
  if (input.validity.valid && input.value) selected.value = input.value
  else input.value = selected.value
}
const hasObservations = computed(() => Object.values(days.value).some(day => day.phone != null || day.pc != null))
const activeDayCount = computed(() => months.value.reduce((count, month) =>
  month.dates.reduce((sum, day) => sum + Number(day.marks.length > 0), count), 0))
const detailHeading = ref<HTMLElement>()
const calendar = ref<HTMLElement>()
function focusDay() { calendar.value?.querySelector<HTMLButtonElement>('[aria-pressed="true"]')?.focus() }
async function openDay(date: string) {
  selected.value = date
  await nextTick()
  detailHeading.value?.focus()
}
const dayOffsets: Record<string, number> = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -7, ArrowDown: 7 }
async function navigateDays(event: KeyboardEvent) {
  const offset = dayOffsets[event.key]
  if (!offset || event.ctrlKey || event.altKey || event.metaKey) return
  event.preventDefault()
  const target = shiftDate(selected.value, offset)
  if (target <= today && target.startsWith(period.value + '-')) {
    selected.value = target
    await nextTick()
    focusDay()
  }
}
</script>

<template>
  <header class="page-heading calendar-heading">
    <div><h1>Календарь активности</h1><p>Тренировки и привычки с телефона, программы и сайты на ПК.</p></div>
    <div class="calendar-toolbar">
      <div class="period-buttons" role="group" aria-label="Вид календаря">
        <button type="button" :aria-pressed="view === 'month'" @click="view = 'month'">Месяц</button>
        <button type="button" :aria-pressed="view === 'year'" @click="view = 'year'">Год</button>
      </div>
      <div class="date-control">
        <button type="button" class="button arrow-button" :aria-label="view === 'year' ? 'Предыдущий год' : 'Предыдущий месяц'" :disabled="period === '0001' || period === '0001-01'" @click="movePeriod(-1)">‹</button>
        <label><span class="sr-only">Выбрать дату</span><input type="date" required min="0001-01-01" :max="today" :value="selected" @change="pickDate"></label>
        <button type="button" class="button arrow-button" :aria-label="view === 'year' ? 'Следующий год' : 'Следующий месяц'" :disabled="today.startsWith(period)" @click="movePeriod(1)">›</button>
      </div>
    </div>
  </header>
  <RequestState v-if="state.status === 'loading'" loading />
  <RequestState v-else-if="state.status === 'error'" :message="state.message" @retry="retry" />
  <div v-else-if="state.status === 'ready'" class="calendar-layout">
    <section class="panel calendar-overview" aria-labelledby="calendar-period">
      <div class="panel-heading">
        <h2 id="calendar-period" class="calendar-title">{{ view === 'year' ? period : months[0].label }}</h2>
        <span v-if="hasObservations" class="calendar-view-label">Дней с активностью: {{ activeDayCount }}</span>
      </div>
      <ul class="activity-legend" aria-label="Метки активности">
        <li v-for="activity in activities" :key="activity.key"><i :data-activity="activity.key" aria-hidden="true"></i>{{ activity.label }}</li>
      </ul>
      <p v-if="!hasObservations" class="empty-notice">За {{ view === 'year' ? 'этот год' : 'этот месяц' }} нет данных. Выберите другой период или обновите календарь по USB.</p>
      <p id="calendar-keys" class="sr-only">Выбирайте день стрелками. Нажмите Enter, чтобы перейти к итогам дня.</p>
      <div ref="calendar" class="calendar-months" :class="{ 'calendar-year': view === 'year' }" role="group" aria-label="Дни календаря" aria-describedby="calendar-keys" @keydown="navigateDays">
        <div v-for="month in months" :key="month.prefix">
          <h3 v-if="view === 'year'" class="calendar-title">{{ month.label }}</h3>
          <div class="calendar-grid">
            <span v-for="weekday in weekdays" :key="weekday" class="calendar-weekday" aria-hidden="true">{{ weekday }}</span>
            <button v-for="({ date, marks, observed, label }, i) in month.dates" :key="date" class="calendar-day" type="button"
              :style="i === 0 ? { gridColumnStart: month.firstColumn } : undefined"
              :data-observed="observed" :data-active="marks.length > 0"
              :disabled="date > today" :tabindex="date === selected ? 0 : -1" :aria-pressed="date === selected"
              :aria-current="date === today ? 'date' : undefined" :aria-label="label" :title="label" @click="openDay(date)">
              <span class="day-number" :data-activity="marks.length === 1 ? marks[0].key : undefined">{{ i + 1 }}</span>
              <span class="day-marks" aria-hidden="true"><i v-for="mark in marks" :key="mark.key" :data-activity="mark.key"></i></span>
            </button>
          </div>
        </div>
      </div>
      <p class="calendar-key">Пунктир — нет данных. Ячейка без цветной полосы — активность не отмечена.</p>
    </section>
    <section class="panel calendar-detail" aria-labelledby="day-heading">
      <div class="day-orientation"><span>Выбранный день</span><button class="calendar-return" type="button" @click="focusDay">К календарю</button></div>
      <h2 id="day-heading" ref="detailHeading" tabindex="-1">{{ dateLabel(selected) }}</h2>
      <section class="day-section" aria-labelledby="pc-heading">
        <div class="day-section-head"><h3 id="pc-heading"><i data-activity="pc" aria-hidden="true"></i>Активность ПК</h3>
          <span v-if="pcGroups.length" class="pc-total"><span class="sr-only">Всего за ПК: </span>{{ durationLabel(pcTotal, true) }}</span>
        </div>
        <div v-if="pcGroups.length" :key="selected" class="pc-groups">
          <details v-for="group in pcGroups" :key="group.key" class="pc-group">
            <summary><span>{{ group.label }}</span><strong>{{ durationLabel(group.seconds, true) }}</strong></summary>
            <p v-if="group.items == null" class="group-note">Разбивка по программам и сайтам за этот день недоступна.</p>
            <ul v-else-if="group.items.length" class="pc-items" :aria-label="`${group.label}: программы и сайты`">
              <li v-for="item in group.items" :key="item.name"><bdi>{{ item.name }}</bdi><span>{{ durationLabel(item.seconds, true) }}</span></li>
            </ul>
            <p v-else class="group-note">В этой группе нет учтённого времени.</p>
          </details>
        </div>
        <p v-else class="insight-note">За этот день нет данных об активности ПК.</p>
      </section>
      <section class="day-section" aria-labelledby="phone-heading">
        <div class="day-section-head"><h3 id="phone-heading">Телефон</h3></div>
        <dl v-if="phone" class="day-values">
          <div><dt><i data-activity="training" aria-hidden="true"></i>Завершённые тренировки</dt><dd>{{ numberLabel(phone.workouts) }}</dd></div>
          <div v-for="kind in workoutDetails(phone)" :key="kind.key" class="day-subtotal"><dt>{{ kind.label }}</dt><dd>{{ numberLabel(kind.count) }}</dd></div>
          <div><dt><i data-activity="math" aria-hidden="true"></i>Математика</dt><dd>{{ numberLabel(mathTotal(phone)) }}</dd></div>
          <div class="day-subtotal"><dt>Пройдено тем</dt><dd>{{ numberLabel(phone.topics) }}</dd></div>
          <div class="day-subtotal"><dt>Решено задач</dt><dd>{{ numberLabel(phone.tasks) }}</dd></div>
          <div class="day-subtotal"><dt>Повторений</dt><dd>{{ numberLabel(phone.reviews) }}</dd></div>
          <div><dt><i data-activity="reading" aria-hidden="true"></i>Чтение</dt><dd>{{ numberLabel(phone.pages) }} стр.</dd></div>
          <div><dt><i data-activity="shower" aria-hidden="true"></i>Холодный душ</dt><dd>{{ durationLabel(phone.showerDurationSec, true) }}</dd></div>
          <div><dt><i data-activity="steps" aria-hidden="true"></i>Шаги</dt><dd>{{ phone.steps == null ? 'Нет данных' : numberLabel(phone.steps) }}</dd></div>
          <div><dt><i data-activity="training" aria-hidden="true"></i>Повторения · верх тела</dt><dd>{{ phone.upperReps == null ? 'Нет данных' : numberLabel(phone.upperReps) }}</dd></div>
          <div><dt><i data-activity="training" aria-hidden="true"></i>Повторения · низ тела</dt><dd>{{ phone.lowerReps == null ? 'Нет данных' : numberLabel(phone.lowerReps) }}</dd></div>
        </dl>
        <p v-else class="insight-note">За этот день нет данных с телефона.</p>
      </section>
    </section>
  </div>
</template>
