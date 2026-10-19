import { describe, expect, it } from 'vitest'
import { problemTagTone } from './tagPalette'

describe('problem tag palette', () => {
  it('uses semantic colors for difficulty labels', () => {
    expect(problemTagTone('简单')).toBe('success')
    expect(problemTagTone('中等')).toBe('warning')
    expect(problemTagTone('困难')).toBe('danger')
  })

  it('keeps common algorithm tags visually distinct', () => {
    const tones = ['数组', '哈希表', '字符串', '动态规划', '双指针'].map(problemTagTone)
    expect(new Set(tones).size).toBe(tones.length)
  })

  it('gives the extended data-structure tags distinct accents', () => {
    const tones = ['链表', '二叉树', '单调栈', '滑动窗口', '回溯', '位运算', '递归'].map(problemTagTone)
    expect(new Set(tones).size).toBe(tones.length)
  })

  it('assigns unknown tags deterministically', () => {
    expect(problemTagTone('图论')).toBe(problemTagTone('图论'))
  })
})
