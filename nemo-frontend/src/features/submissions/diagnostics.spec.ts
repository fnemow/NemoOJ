import { describe, expect, it } from 'vitest'
import { diagnosticEntries, formatDiagnostic, isUserDiagnosticLocation } from './diagnostics'

describe('submission diagnostics', () => {
  it('hides container paths and redundant compile heading', () => {
    const raw = "编译错误:\n/app/Solution.java:4: error: ';' expected\n    return 3\n            ^\n1 error"
    expect(formatDiagnostic(raw)).toBe("Solution.java:4: error: ';' expected\n    return 3\n            ^\n1 error")
  })

  it('keeps filename, line and column for URI diagnostics', () => {
    expect(formatDiagnostic('\u001b[31mfile:///app/main.cpp:2:3: error: boom\u001b[0m'))
      .toBe('main.cpp:2:3: error: boom')
  })

  it('groups GCC errors and notes without linking generated files or out-of-range lines', () => {
    const entries = diagnosticEntries("main.cpp:17:16: error: invalid conversion from 'int' to 'int*'\n   17 | int* a,b=-1;\n      |          ^\nmain.cpp:3:40: note: initializing argument 4\nrunner.cpp:120:1: error: internal")
    expect(entries).toHaveLength(3)
    expect(entries[0]).toMatchObject({ line: 17, column: 16, kind: 'error' })
    expect(entries[0]?.context).toContain('int* a,b=-1;')
    expect(entries[1]?.kind).toBe('note')
    expect(isUserDiagnosticLocation(entries[0]!, 'ACM', 30)).toBe(true)
    expect(isUserDiagnosticLocation(entries[0]!, 'ACM', 10)).toBe(false)
    expect(isUserDiagnosticLocation(entries[2]!, 'FUNCTION', 200)).toBe(false)
  })

  it('preserves Python syntax reasons and interface errors without invented lines', () => {
    expect(diagnosticEntries('  File "/app/solution.py", line 3\n    return (\n           ^\nSyntaxError: never closed')[0])
      .toMatchObject({ line: 3, message: 'SyntaxError: never closed', context: '    return (\n           ^' })
    expect(diagnosticEntries('题目接口校验：error: cannot find symbol\n  symbol: method solve(int[])')[0])
      .toMatchObject({ line: null, message: 'cannot find symbol' })
    expect(diagnosticEntries('Unknown compiler response')).toEqual([])
  })
})
