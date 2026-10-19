import type { LanguageId } from '@/api/types'

export interface SourceToken { text: string; kind: string }
export interface SourceLine { number: number; tokens: SourceToken[] }
const keywords = new Set(('public private protected static final class interface extends implements new return if else for while switch case break continue try catch throw throws null true false auto bool const default do double enum float int long namespace nullptr struct template using void def elif in except raise with as import from lambda None True False and or not pass is async await yield global nonlocal del assert finally package synchronized volatile this super sizeof char short unsigned signed typedef union virtual override include').split(' '))
const types = new Set(['String', 'Integer', 'Long', 'Double', 'Boolean', 'List', 'Map', 'Set', 'ArrayList', 'HashMap', 'vector', 'string', 'deque', 'queue', 'stack', 'set', 'unordered_map', 'std', 'System', 'Scanner'])

/** Small, bounded lexical reader. Text stays in Vue text nodes, never HTML. */
export function presentSource(source: string, language: LanguageId): SourceLine[] {
  const text = source.replace(/\r\n?/g, '\n')
  const pattern = language === 'python'
    ? /#[^\n]*|"""[\s\S]*?(?:"""|$)|'''[\s\S]*?(?:'''|$)|"(?:\\[\s\S]|[^"\\\n])*"?|'(?:\\[\s\S]|[^'\\\n])*'?|\b(?:0[xX][\da-fA-F]+|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)\b|\b[A-Za-z_]\w*\b/g
    : /\/\/[^\n]*|\/\*[\s\S]*?(?:\*\/|$)|"(?:\\[\s\S]|[^"\\\n])*"?|'(?:\\[\s\S]|[^'\\\n])*'?|\b(?:0[xX][\da-fA-F]+|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?[uUlLfF]*)\b|\b[A-Za-z_]\w*\b/g
  const tokens: SourceToken[] = []
  let end = 0, count = 0
  const scanned = text.slice(0, 120_000)
  for (const match of scanned.matchAll(pattern)) {
    if (++count > 25_000) break
    const start = match.index, value = match[0]
    if (start > end) tokens.push({ text: text.slice(end, start), kind: '' })
    const comment = language === 'python' ? value.startsWith('#') : value.startsWith('//') || value.startsWith('/*')
    const kind = comment ? 'comment' : /^["']/.test(value) ? 'string' : /^\d/.test(value) ? 'number'
      : keywords.has(value) ? 'keyword' : types.has(value) || /^[A-Z]/.test(value) ? 'type'
        : /^\s*\(/.test(scanned.slice(start + value.length)) ? 'function' : ''
    tokens.push({ text: value, kind }); end = start + value.length
  }
  if (end < text.length) tokens.push({ text: text.slice(end), kind: '' })
  const lines: SourceLine[] = [{ number: 1, tokens: [] }]
  for (const token of tokens) {
    const parts = token.text.split('\n')
    parts.forEach((part, index) => {
      if (index) lines.push({ number: lines.length + 1, tokens: [] })
      if (part) lines[lines.length - 1]!.tokens.push({ text: part, kind: token.kind })
    })
  }
  return lines
}
