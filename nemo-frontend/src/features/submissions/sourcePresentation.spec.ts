import { describe, expect, it } from 'vitest'
import { presentSource } from './sourcePresentation'

describe('read-only source presentation', () => {
  it.each(['java', 'cpp', 'python'] as const)('preserves source text, indentation and trailing newline for %s', language => {
    const source = 'class Solution {\r\n\treturn "<script> & 42";\r\n}\r\n'
    const lines = presentSource(source, language)
    expect(lines.map(line => line.tokens.map(token => token.text).join('')).join('\n')).toBe(source.replaceAll('\r\n', '\n'))
    expect(lines[0]?.tokens.find(token => token.text === 'class')?.kind).toBe('keyword')
    expect(lines[1]?.tokens.find(token => token.kind === 'string')?.text).toBe('"<script> & 42"')
  })
  it('keeps multiline comments and Python strings colored across lines', () => {
    expect(presentSource('/* int\nreturn */\nreturn 42;', 'cpp')[1]?.tokens[0]?.kind).toBe('comment')
    expect(presentSource('s = """def\nclass"""\n# return', 'python')[1]?.tokens[0]?.kind).toBe('string')
  })
  it('preserves large source after the highlighting limit', () => {
    const source = 'int x = 1;\n'.repeat(15_000)
    expect(presentSource(source, 'cpp').map(line => line.tokens.map(token => token.text).join('')).join('\n')).toBe(source)
  })
})
