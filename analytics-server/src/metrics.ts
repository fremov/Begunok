interface Metric {
  name: string
  unit: string
  description: string
  total?: boolean
  scale?: number
  digits?: number
  duration?: 'minutes' | 'seconds'
  max?: number
}

export const metrics = {
  pulse: { name: 'Пульс', unit: 'уд/мин', description: 'Средний пульс за день по доступным измерениям.' },
  oxygen: { name: 'Кислород (SpO₂)', unit: '%', digits: 1, description: 'Среднее насыщение крови кислородом за день.' },
  stress: { name: 'Стресс браслета', unit: 'балл.', digits: 1, max: 100, description: 'Средняя оценка стресса за день по измерениям браслета.' },
  steps: { name: 'Шаги', unit: 'шаг.', total: true, description: 'Число шагов за день по данным Health Connect.' },
  active_kcal: { name: 'Активные калории', unit: 'ккал', total: true, description: 'Энергия активности за день без учёта основного обмена.' },
  distance: { name: 'Расстояние', unit: 'км', total: true, digits: 2, scale: 1000, description: 'Расстояние за день по данным Health Connect.' },
  sleep: { name: 'Сон', unit: 'мин', duration: 'minutes', total: true, scale: 60, description: 'Сумма длительностей сеансов по дате пробуждения, без известных периодов бодрствования.' },
  shower: { name: 'Холодный душ', unit: 'мин', duration: 'seconds', total: true, scale: 60, description: 'Суммарная длительность записанных сеансов за день.' },
  weight: { name: 'Вес', unit: 'кг', digits: 1, description: 'Средний вес по взвешиваниям за день.' },
} satisfies Record<string, Metric>

export type Stream = keyof typeof metrics
export const streams = Object.keys(metrics) as Stream[]
export const metricGroups: { name: string; streams: Stream[] }[] = [
  { name: 'Показатели организма', streams: ['pulse', 'oxygen', 'stress'] },
  { name: 'Активность', streams: ['steps', 'active_kcal', 'distance'] },
  { name: 'Сон и привычки', streams: ['sleep', 'shower', 'weight'] },
]

export const DAY = 86_400_000
export const dayStamp = (date: string) => Date.parse(`${date}T00:00:00Z`)
export const isoDate = (stamp: number) => new Date(stamp).toISOString().slice(0, 10)
export const shiftDate = (date: string, days: number) => isoDate(dayStamp(date) + days * DAY)
export const localToday = () => {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
}
export const recentDates = (end: string, days: number) =>
  Array.from({ length: days }, (_, i) => shiftDate(end, i + 1 - days))

const fullDate = new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
const shortDate = new Intl.DateTimeFormat('ru-RU', { day: '2-digit', month: '2-digit', timeZone: 'UTC' })
const numberFormats = [0, 1, 2].map(maximumFractionDigits => new Intl.NumberFormat('ru-RU', { maximumFractionDigits }))

export const dateLabel = (date: string) => fullDate.format(dayStamp(date))
export const shortDateLabel = (date: string) => shortDate.format(dayStamp(date))
export const rangeLabel = (from: string | null, through: string | null) =>
  !from || !through ? 'Нет записей' : from === through ? dateLabel(from) : `${dateLabel(from)} — ${dateLabel(through)}`
export const numberLabel = (value: number, digits = 0) => numberFormats[digits].format(value)
export const timeLabel = (time?: string) => time?.match(/T(\d{2}:\d{2})/)?.[1]

export function durationLabel(seconds: number, precise = false): string {
  const total = precise ? Math.round(seconds) : Math.round(seconds / 60) * 60
  const h = Math.floor(total / 3600)
  const m = Math.floor(total / 60) % 60
  const s = total % 60
  return [h && `${h} ч`, m && `${m} мин`, precise && s && `${s} с`].filter(Boolean).join(' ') || (precise ? '0 с' : '0 мин')
}

export const metricFor = (stream: Stream): Metric => metrics[stream]

export function formatValue(stream: Stream, value: number | null | undefined): string {
  if (value == null) return '—'
  const metric = metricFor(stream)
  const formatted = metric.duration
    ? durationLabel(Math.abs(value), metric.duration === 'seconds')
    : `${numberLabel(Math.abs(value) / (metric.scale ?? 1), metric.digits)} ${metric.unit}`
  return value < 0 ? `−${formatted}` : formatted
}
