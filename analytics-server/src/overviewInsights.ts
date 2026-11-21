import type { DayPoint } from './api'
import { dateLabel, formatValue, shiftDate, type Stream } from './metrics'

export interface Insight {
  label: string
  detail: string
}

function mean(points: DayPoint[]) {
  return points.reduce((sum, point) => sum + point.value, 0) / points.length
}

function difference(stream: Stream, value: number, baseline: number) {
  const delta = value - baseline
  const magnitude = formatValue(stream, Math.abs(delta))
  if (magnitude === formatValue(stream, 0)) return 'На том же уровне'
  const change = stream === 'oxygen' ? magnitude.replace('%', 'п. п.') : magnitude
  return `На ${change} ${delta > 0 ? 'выше' : 'ниже'}`
}

export function overviewInsights(stream: Stream, days: DayPoint[], today: string): Insight[] {
  const last = days.at(-1)
  if (!last) return []

  const date = last.date === today ? 'Сегодня' : dateLabel(last.date)
  const previous = days.at(-2)
  const insights: Insight[] = []
  if (previous) {
    insights.push({
      label: `${date} · к предыдущему дню с данными`,
      detail: `${difference(stream, last.value, previous.value)} · ${dateLabel(previous.date)}: ${formatValue(stream, previous.value)}`,
    })
  }
  for (const [span, minimum] of [[7, 3], [28, 7]] as const) {
    const start = shiftDate(last.date, -span)
    const baseline = days.filter(point => point.date >= start && point.date < last.date)
    if (baseline.length < minimum) continue
    const average = mean(baseline)
    insights.push({
      label: `${date} · к среднему за предыдущие ${span} дней`,
      detail: `${difference(stream, last.value, average)} · среднее ${formatValue(stream, average)} (${baseline.length}/${span} дней с данными)`,
    })
  }

  // Weekly averages use complete calendar days and never treat missing days as zero.
  const end = shiftDate(today, -1)
  const start = shiftDate(end, -6)
  const earlier = shiftDate(start, -7)
  const recentWeek = days.filter(point => point.date >= start && point.date <= end)
  const priorWeek = days.filter(point => point.date >= earlier && point.date < start)
  if (recentWeek.length >= 3 && priorWeek.length >= 3) {
    const current = mean(recentWeek)
    const baseline = mean(priorWeek)
    insights.push({
      label: 'Последние 7 полных дней · к неделе до них',
      detail: `${difference(stream, current, baseline)} · ${formatValue(stream, current)} против ${formatValue(stream, baseline)} (${recentWeek.length}/7 и ${priorWeek.length}/7 дней)`,
    })
  }

  if (days.length >= 3) {
    const low = days.reduce((best, point) => point.value <= best.value ? point : best)
    const high = days.reduce((best, point) => point.value >= best.value ? point : best)
    if (formatValue(stream, low.value) !== formatValue(stream, high.value)) {
      insights.push({
        label: 'Минимум и максимум на графике',
        detail: `${formatValue(stream, low.value)} (${dateLabel(low.date)}) · ${formatValue(stream, high.value)} (${dateLabel(high.date)})`,
      })
    }
  }
  return insights
}
