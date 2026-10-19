import { describe,expect,it } from 'vitest'
import { dataStructureTemplates,planTemplateInsertion } from './dataStructureTemplates'

describe('data structure templates',()=>{
  it('offers eight templates for all supported languages',()=>{
    expect(new Set(dataStructureTemplates.map(item=>item.id)).size).toBe(8)
    for(const item of dataStructureTemplates)for(const language of ['java','python','cpp'] as const)
      expect(item[language]).toContain(item.id)
  })
  it('keeps Java package and multiple imports ahead of the new class',()=>{
    const source='package demo;\nimport java.util.*; import java.io.*;\nclass Solution {}'
    const insertion=planTemplateInsertion(source,'java','RBNode')!
    const changed=source.slice(0,insertion.offset)+insertion.text+source.slice(insertion.offset)
    expect(changed.indexOf('class RBNode')).toBeGreaterThan(changed.indexOf('import java.io.*;'))
    expect(changed.replace(insertion.text,'')).toBe(source)
  })
  it.each([
    '#!/usr/bin/python\n"""module docs"""\nfrom __future__ import annotations\nclass Solution: pass',
    '"""docs"""\nfrom __future__ import (\n annotations,\n)\nclass Solution: pass',
    '"""docs"""\nfrom __future__ import (annotations)\nclass Solution: pass',
  ])('preserves Python future imports and module docstrings',source=>{
    const insertion=planTemplateInsertion(source,'python','UnionFind')!
    const changed=source.slice(0,insertion.offset)+insertion.text+source.slice(insertion.offset)
    expect(changed.indexOf('class UnionFind')).toBeGreaterThan(changed.indexOf('annotations'))
    expect(changed.replace(insertion.text,'')).toBe(source)
  })
  it('preserves standalone Python docstrings and encoding headers',()=>{
    const source='# coding: utf-8\n"""module docs"""\nprint(1)'
    expect(planTemplateInsertion(source,'python','TrieNode')?.offset).toBe(source.indexOf('\nprint'))
  })
  it('refuses duplicate definitions, but ignores mentions in comments and strings',()=>{
    expect(planTemplateInsertion('class RBNode {}','java','RBNode')).toBeNull()
    expect(planTemplateInsertion('// class RBNode {}\nclass Solution {}','java','RBNode')).not.toBeNull()
    expect(planTemplateInsertion('s="class RBNode:"','python','RBNode')).not.toBeNull()
    expect(planTemplateInsertion('','cpp','Unknown')).toBeNull()
  })
  it('supplies C++ headers and supports repeated insertion without losing the draft',()=>{
    const source='class Solution {};'
    const insertion=planTemplateInsertion(source,'cpp','UnionFind')!
    const changed=insertion.text+source
    expect(changed).toContain('#include <vector>')
    expect(changed).toContain('#include <stdexcept>')
    expect(planTemplateInsertion(changed,'cpp','UnionFind')).toBeNull()
    expect(planTemplateInsertion(changed,'cpp','FenwickTree')).not.toBeNull()
    expect(changed.endsWith(source)).toBe(true)
  })
})
