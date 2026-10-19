import { describe, expect, it } from 'vitest'
import { activityLevel, calendarDays, shiftMonth, shanghaiDate, activityTooltip } from './activity'

describe('monthly submission calendar', () => {
  it('uses Shanghai civil dates around midnight rather than the browser timezone', () => {
    expect(shanghaiDate(new Date('2026-09-30T16:01:00Z'))).toBe('2026-10-01')
  })
  it('aligns Monday-first calendars and includes leap days without inventing padding dates', () => {
    const days = calendarDays('2024-02')
    expect(days.slice(0,3)).toEqual([null,null,null])
    expect(days.filter(Boolean)).toHaveLength(29)
    expect(days).toContain('2024-02-29')
    expect(days.length % 7).toBe(0)
  })
  it('changes year at month boundaries', () => {
    expect(shiftMonth('2026-01', -1)).toBe('2025-12')
    expect(shiftMonth('2025-12', 1)).toBe('2026-01')
  })
  it('keeps empty days gray and increases blue intensity at stable thresholds', () => {
    expect([0,1,3,4,7,8,15,16].map(activityLevel)).toEqual([0,1,1,2,2,3,3,4])
  })
  it('reports both different problems and total attempts in the tooltip', () => {
    expect(activityTooltip({date:'2026-10-12',submissions:5,problems:2})).toBe('2026年10月12日：提交了2道题目，共5次提交')
  })
})
