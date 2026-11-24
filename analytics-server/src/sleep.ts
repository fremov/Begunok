import type { SleepPhase, SleepRecord } from './api'

export const phases = [
  ['out_of_bed', 'Вне постели'], ['awake', 'Бодрствование'], ['rem', 'REM'],
  ['light', 'Лёгкий сон'], ['deep', 'Глубокий сон'], ['sleeping', 'Без фазы'], ['unknown', 'Нет данных'],
] as const
const rawPhases = new Map<string, number>(phases.map(([key], i) => [key, i]))
export const comparedPhases: SleepPhase[] = ['light', 'deep', 'rem', 'awake']
export const phaseIndex = (key: SleepPhase) => rawPhases.get(key)!
const MINUTE_MS = 60_000

export function sleepAnalysis(record: SleepRecord) {
  const start = Date.parse(record.start)
  const end = Date.parse(record.end)
  const segments = record.segments.map(([left, right, key]) => ({
    start: start + left * 1000, end: start + right * 1000, phase: rawPhases.get(key) ?? phases.length - 1,
  }))
  const sourceOffset = /([+-])(\d{2}):?(\d{2})$/.exec(record.start)
  const offset = sourceOffset ? (sourceOffset[1] === '-' ? -1 : 1) * (Number(sourceOffset[2]) * 60 + Number(sourceOffset[3])) : 0
  return { id: record.id, start, end, offset, segments, seconds: record.value,
    startLabel: record.start.slice(11, 16), endLabel: record.end.slice(11, 16) }
}

export function clock(stamp: number, offset: number) {
  return new Date(stamp + offset * MINUTE_MS).toISOString().slice(11, 16)
}

export function offsetLabel(offset: number) {
  if (!offset) return 'UTC'
  const absolute = Math.abs(offset)
  return `UTC${offset < 0 ? '−' : '+'}${String(Math.floor(absolute / 60)).padStart(2, '0')}:${String(absolute % 60).padStart(2, '0')}`
}
