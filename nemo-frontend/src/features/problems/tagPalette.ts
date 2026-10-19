export type ProblemTagTone =
  | 'success' | 'warning' | 'danger'
  | 'cyan' | 'violet' | 'rose' | 'orange' | 'teal'
  | 'indigo' | 'purple' | 'sky' | 'emerald' | 'lime' | 'slate'

const semanticTones: Record<string, ProblemTagTone> = {
  简单: 'success',
  中等: 'warning',
  困难: 'danger',
  数组: 'cyan',
  哈希表: 'violet',
  字符串: 'rose',
  栈: 'orange',
  二分查找: 'indigo',
  动态规划: 'purple',
  双指针: 'teal',
  数学: 'lime',
  矩阵: 'sky',
  深度优先搜索: 'emerald',
  广度优先搜索: 'lime',
  排序: 'orange',
  链表: 'violet',
  递归: 'rose',
  二叉树: 'emerald',
  单调栈: 'orange',
  滑动窗口: 'sky',
  回溯: 'purple',
  位运算: 'lime',
  图论: 'indigo',
  堆与贪心: 'orange',
  前缀和与区间查询: 'teal',
}

// The shared catalogue covers the tags used by the algorithm problem batches.
export const problemTagNames: readonly string[] = Object.keys(semanticTones)

const fallbackTones: ProblemTagTone[] = [
  'cyan', 'violet', 'rose', 'orange', 'teal', 'indigo', 'purple', 'sky', 'emerald', 'lime', 'slate',
]

export function problemTagTone(tag: string): ProblemTagTone {
  const known = semanticTones[tag.trim()]
  if (known) return known
  let hash = 0
  for (const character of tag) hash = (hash * 31 + character.codePointAt(0)!) >>> 0
  return fallbackTones[hash % fallbackTones.length] ?? 'slate'
}
