import type { DayPoint, Series, SeriesWindow } from './api'
import { DAY, dayStamp, isoDate, metricFor } from './metrics'

function buildWindow(source: SeriesWindow, total: boolean) {
  const { start, end } = source
  const lookup = new Map<string, DayPoint>()
  let count = 0
  if (start && end) {
    for (const { date, value } of source.records) {
      if (value === undefined || !Number.isFinite(value) || date < start || date > end) continue
      const existing = lookup.get(date)
      if (existing) {
        existing.value += value
        existing.count++
      } else {
        lookup.set(date, { date, value, count: 1 })
      }
      count++
    }
  }
  const points = [...lookup.values()].sort((a, b) => a.date.localeCompare(b.date))
  let sum = 0
  for (const point of points) {
    if (!total) point.value /= point.count
    sum += point.value
  }
  return { start, end, lookup, points, count, sum, mean: points.length ? sum / points.length : null }
}

export function buildHistory(series: Series) {
  const metric = metricFor(series.stream)
  const current = buildWindow(series.current, !!metric.total)
  const previous = series.previous ? buildWindow(series.previous, !!metric.total) : null
  const displacement = current.end && previous?.end ? dayStamp(current.end) - dayStamp(previous.end) : 0
  const aligned: DayPoint[] = []
  if (current.start && current.end && previous) {
    const start = dayStamp(current.start)
    const end = dayStamp(current.end)
    for (const point of previous.points) {
      const stamp = dayStamp(point.date) + displacement
      if (stamp >= start && stamp <= end) aligned.push({ ...point, date: isoDate(stamp) })
    }
  }
  return { current, previous, displacement, aligned, lifetimeMean: series.all_time.daily_mean }
}

export function timeTrace(points: DayPoint[], scale = 1): [number, number | null][] {
  const result: [number, number | null][] = []
  let previous: number | undefined
  for (const point of points) {
    const stamp = dayStamp(point.date)
    if (previous !== undefined && stamp - previous > DAY) result.push([previous + DAY, null])
    result.push([stamp, point.value / scale])
    previous = stamp
  }
  return result
}

export function isolatedPoints(points: DayPoint[]): DayPoint[] {
  return points.filter((point, i) => {
    const stamp = dayStamp(point.date)
    return !(i > 0 && stamp - dayStamp(points[i - 1].date) === DAY)
      && !(i + 1 < points.length && dayStamp(points[i + 1].date) - stamp === DAY)
  })
}
