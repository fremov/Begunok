import type { SleepRecord } from './api'

export const phases = [
  ['out_of_bed', 'Вне постели'], ['awake', 'Бодрствование'], ['rem', 'REM'],
  ['light', 'Лёгкий'], ['deep', 'Глубокий'], ['sleeping', 'Без фазы'], ['unknown', 'Нет данных'],
] as const
const rawPhases = new Map<string, number>(phases.map(([key], i) => [key, i]))
const OUT = 0, AWAKE = 1, REM = 2, LIGHT = 3, DEEP = 4, SLEEPING = 5, UNKNOWN = 6
const MINUTE_MS = 60_000

export function sleepAnalysis(record: SleepRecord) {
  const start = Date.parse(record.start)
  const end = Date.parse(record.end)
  if (!(start < end)) return null
  const events = [{ at: start, phase: UNKNOWN, change: 0 }, { at: end, phase: UNKNOWN, change: 0 }]
  for (const stage of record.stages) {
    const from = Math.max(start, Date.parse(stage.start))
    const through = Math.min(end, Date.parse(stage.end))
    if (!(from < through)) continue
    const phase = rawPhases.get(stage.stage) ?? UNKNOWN
    events.push({ at: from, phase, change: 1 }, { at: through, phase, change: -1 })
  }
  events.sort((a, b) => a.at - b.at)
  const active = phases.map(() => 0)
  const segments: { start: number; end: number; phase: number }[] = []
  let awake = 0
  let from = start
  for (const { at, phase, change } of events) {
    if (at > from) {
      const detailed = active[REM] || active[LIGHT] || active[DEEP]
      let resolved = UNKNOWN
      for (let i = 0; i < UNKNOWN; i++) {
        if (!active[i] || (i === SLEEPING && detailed) || (i === AWAKE && active[OUT])) continue
        if (resolved !== UNKNOWN) {
          resolved = UNKNOWN
          break
        }
        resolved = i
      }
      if (active[AWAKE] || active[OUT]) awake += at - from
      const prior = segments.at(-1)
      if (prior?.phase === resolved) prior.end = at
      else segments.push({ start: from, end: at, phase: resolved })
      from = at
    }
    active[phase] += change
  }
  const sourceOffset = /([+-])(\d{2}):?(\d{2})$/.exec(record.start)
  const offset = sourceOffset ? (sourceOffset[1] === '-' ? -1 : 1) * (Number(sourceOffset[2]) * 60 + Number(sourceOffset[3])) : 0
  return { id: record.id, start, end, offset, segments, seconds: (end - start - awake) / 1000 }
}

export function clock(stamp: number, offset: number) {
  return new Date(stamp + offset * MINUTE_MS).toISOString().slice(11, 16)
}

export function offsetLabel(offset: number) {
  if (!offset) return 'UTC'
  const absolute = Math.abs(offset)
  return `UTC${offset < 0 ? '−' : '+'}${String(Math.floor(absolute / 60)).padStart(2, '0')}:${String(absolute % 60).padStart(2, '0')}`
}
