import { inject, shallowRef, watch, type InjectionKey, type Ref } from 'vue'
import type { Stream } from './metrics'

export const refreshKey: InjectionKey<Readonly<Ref<number>>> = Symbol('analytics refresh')

export interface DayPoint {
  date: string
  value: number
  count: number
}

export interface Overview {
  series: Record<Stream, {
    latest: { date: string; time?: string; value: number } | null
    days: DayPoint[]
  }>
}

export const periods = [
  ['week', 'Неделя'], ['month', 'Месяц'], ['quarter', '3 месяца'],
  ['year', 'Год'], ['all', 'Всё время'],
] as const
export type Period = typeof periods[number][0]

export interface SeriesWindow {
  start: string | null
  end: string | null
  records: { date: string; value?: number }[]
}

export interface Series {
  stream: Stream
  period: Period
  current: SeriesWindow
  previous: SeriesWindow | null
  all_time: { daily_mean: number | null }
}

export interface SleepRecord {
  id: string
  start: string
  end: string
  stages: { start: string; end: string; stage: string }[]
}

export interface SleepDetails {
  date: string
  records: SleepRecord[]
  comparison: {
    minutes: number | null
    previous_date: string | null
    previous_minutes: number | null
    earlier_mean_minutes: number | null
  }
}

export async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { cache: 'no-store', ...init })
  if (!response.ok) {
    if (response.status === 409) {
      const result = await response.json() as { error?: string }
      if (result.error) throw new Error(result.error)
    }
    throw new Error(`Ошибка локального сервера (${response.status}).`)
  }
  return await response.json() as T
}

export function errorMessage(error: unknown): string {
  return error instanceof Error && !(error instanceof TypeError || error instanceof SyntaxError)
    ? error.message
    : 'Проверьте, что локальный сервер запущен, и повторите запрос.'
}

type ResourceState<T> =
  | { status: 'idle' | 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; data: T }

export function useResource<T>(url: () => string | null) {
  const state = shallowRef<ResourceState<T>>({ status: 'idle' })
  const revision = shallowRef(0)
  const refresh = inject(refreshKey, shallowRef(0))
  watch([url, revision, refresh], async ([path], _, onCleanup) => {
    if (!path) {
      state.value = { status: 'idle' }
      return
    }
    const controller = new AbortController()
    onCleanup(() => controller.abort())
    state.value = { status: 'loading' }
    try {
      const data = await request<T>(path, { signal: controller.signal })
      if (!controller.signal.aborted) state.value = { status: 'ready', data }
    } catch (error) {
      if (!controller.signal.aborted) state.value = { status: 'error', message: errorMessage(error) }
    }
  }, { immediate: true })
  return { state, retry: () => revision.value++ }
}
