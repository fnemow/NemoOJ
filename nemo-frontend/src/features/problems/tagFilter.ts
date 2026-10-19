import { problemTagNames } from './tagPalette'

export const difficultyTags = ['简单', '中等', '困难'] as const

export function normalizeTagFilter(value: unknown): string[] {
  const values = Array.isArray(value) ? value : [value]
  return [...new Set(values.filter((tag): tag is string => typeof tag === 'string')
    .map(tag => tag.trim()).filter(Boolean))]
}

export function tagFilterOptions(observed: string[] = [], selected: string[] = []): string[] {
  return normalizeTagFilter([...problemTagNames, ...observed, ...selected])
}
