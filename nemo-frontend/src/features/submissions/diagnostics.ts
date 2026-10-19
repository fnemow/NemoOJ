const ANSI_ESCAPE = new RegExp(`${String.fromCharCode(27)}\\[[0-?]*[ -/]*[@-~]`, 'g')
const COMPILE_HEADING = /^(?:编译错误|compile(?:r)? error)\s*[:：]?\s*(?:\n+)?/i

/** Hide sandbox implementation paths while preserving filenames and source locations. */
export function formatDiagnostic(message: string): string {
  return message
    .replace(/\r\n?/g, '\n')
    .replace(ANSI_ESCAPE, '')
    .replaceAll('file:///app/', '')
    .replaceAll('file:/app/', '')
    .replaceAll('/app/', '')
    .replaceAll('\\app\\', '')
    .replace(COMPILE_HEADING, '')
    .replace(/^\n+/, '')
    .trim()
}

export interface DiagnosticEntry {
  kind: 'error' | 'warning' | 'note'
  message: string
  file: string | null
  line: number | null
  column: number | null
  context: string
}

/** Recognize compiler locations only; source excerpts must never become links. */
export function diagnosticEntries(message: string): DiagnosticEntry[] {
  const entries: DiagnosticEntry[] = []
  let current: DiagnosticEntry | undefined
  for (const text of formatDiagnostic(message).split('\n')) {
    const native = text.match(/^(.+?\.(?:java|cpp|cc|cxx|hpp|h|py)):(\d+)(?::(\d+))?:\s*(?:(fatal error|error|warning|note|错误|警告|注):\s*)?(.*)$/)
    const python = text.match(/^\s*File "([^"]+\.py)", line (\d+)(?:,.*)?$/)
    const internal = text.match(/^题目接口校验：(?:(error|warning|note):\s*)?(.*)$/)
    if (native || python || internal) {
      const severity = native?.[4] ?? internal?.[1]
      current = {
        kind: severity === 'note' || severity === '注' ? 'note' : severity === 'warning' || severity === '警告' ? 'warning' : 'error',
        message: native?.[5] ?? internal?.[2] ?? 'Python 语法错误',
        file: native?.[1] ?? python?.[1] ?? null,
        line: native || python ? Number(native?.[2] ?? python?.[2]) : null,
        column: native?.[3] ? Number(native[3]) : null,
        context: ''
      }
      entries.push(current)
    } else if (current) {
      if (pythonError(text) && current.file?.endsWith('.py')) current.message = text.trim()
      else if (!/^\d+ (?:errors?|warnings?)\.?$/.test(text) && current.context.split('\n').length <= 8)
        current.context += `${text}\n`
    }
  }
  return entries.map(entry => ({ ...entry, context: entry.context.trimEnd() }))
}

function pythonError(text: string) { return /^(?:SyntaxError|IndentationError|TabError):/.test(text) }

export function isUserDiagnosticLocation(entry: DiagnosticEntry, mode: string | null, lineCount: number): boolean {
  const files = mode === 'FUNCTION' ? ['Solution.java', 'solution.hpp', 'solution.py'] : ['Main.java', 'main.cpp', 'main.py']
  return files.includes(entry.file ?? '') && Number.isSafeInteger(entry.line) && entry.line! > 0 && entry.line! <= lineCount
}
