import { describe, expect, it } from 'vitest'
import { draftKey } from './drafts'

describe('draftKey', () => {
  it('isolates drafts by immutable question id and template version', () => {
    const current = draftKey('guest', 'question-42', 1, 'java', 'FUNCTION', 'template-v2')

    expect(current).not.toBe(draftKey('guest', 'question-99', 1, 'java', 'FUNCTION', 'template-v2'))
    expect(current).not.toBe(draftKey('guest', 'question-42', 1, 'java', 'FUNCTION', 'template-v3'))
    expect(current).not.toBe(draftKey('guest', 'question-42', 2, 'java', 'FUNCTION', 'template-v2'))
  })

  it('keeps delimiter characters inside the template version unambiguous', () => {
    expect(draftKey('guest', 'question-42', 1, 'cpp', 'ACM', 'sha:abc/123'))
      .toBe('3:guest:question-42:1:cpp:ACM:sha%3Aabc%2F123')
  })
})
