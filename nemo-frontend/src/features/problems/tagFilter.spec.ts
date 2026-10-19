import { describe, expect, it } from 'vitest'
import { normalizeTagFilter, tagFilterOptions } from './tagFilter'

describe('problem tag filters', () => {
  it('reads existing single-tag and multi-tag links without blank or duplicate tags', () => {
    expect(normalizeTagFilter(' 简单 ')).toEqual(['简单'])
    expect(normalizeTagFilter(['数组', null, '', '数组', '二叉树'])).toEqual(['数组', '二叉树'])
    expect(normalizeTagFilter(undefined)).toEqual([])
  })

  it('offers the complete catalogue regardless of the current results, and includes custom tags', () => {
    const options = tagFilterOptions(['数组', '图论'], ['离散化'])
    expect(options.slice(0, 3)).toEqual(['简单', '中等', '困难'])
    expect(options).toContain('二叉树')
    expect(options).toContain('滑动窗口')
    expect(options).toContain('图论')
    expect(options).toContain('离散化')
    expect(options.filter(tag => tag === '数组')).toHaveLength(1)
    const defaultOptions = tagFilterOptions()
    expect(defaultOptions).toContain('图论')
    expect(defaultOptions).toContain('堆与贪心')
    expect(defaultOptions).toContain('前缀和与区间查询')
    expect(defaultOptions).toHaveLength(25)
  })
})
