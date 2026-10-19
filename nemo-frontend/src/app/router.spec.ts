import { describe,expect,it } from 'vitest'
import { roleAccessRedirect,safeReturnTo } from './router'
describe('safeReturnTo',()=>{
  it('allows local routes',()=>expect(safeReturnTo('/problems/123?tab=code')).toBe('/problems/123?tab=code'))
  it.each(['https://evil.example','//evil.example','/login?returnTo=/admin',null])('rejects unsafe destination %s',value=>expect(safeReturnTo(value)).toBe('/problems'))
})
describe('roleAccessRedirect',()=>{
  it('keeps ordinary users out of admin routes',()=>expect(roleAccessRedirect({admin:true},'user')).toBe('/403'))
  it('keeps administrators out of solving and user feedback routes',()=>expect(roleAccessRedirect({memberOnly:true},'admin')).toBe('/admin/problems'))
  it('allows ordinary users into member routes',()=>expect(roleAccessRedirect({memberOnly:true},'user')).toBeUndefined())
  it('allows administrators into admin routes',()=>expect(roleAccessRedirect({admin:true},'admin')).toBeUndefined())
})
