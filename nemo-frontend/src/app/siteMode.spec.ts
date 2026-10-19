import {describe,it,expect} from 'vitest'
import {filingRedirect, registrationRedirect} from './siteMode'
describe('filing mode',()=>{
  it.each(['/login','/register','/REGISTER/','/admin/users','/study-plans'])('blocks authentication routes %s',path=>{
    expect(filingRedirect(path,path.startsWith('/admin')||path==='/study-plans',true)).toBe('/problems')
  })
  it('keeps public problems available and normal mode unchanged',()=>{
    expect(filingRedirect('/problems/2',false,true)).toBeUndefined()
    expect(filingRedirect('/login',false,false)).toBeUndefined()
  })
})
describe('registration closed independently',()=>{
  it.each(['/register','/register/','/REGISTER/','/register/invitation'])('blocks registration route %s',path=>{
    expect(registrationRedirect(path,false)).toBe('/login')
  })
  it('keeps login, submissions and admin access available',()=>{
    for(const path of ['/login','/submissions','/problems/3','/admin/users']){
      expect(registrationRedirect(path,false)).toBeUndefined()
      expect(filingRedirect(path,true,false)).toBeUndefined()
    }
  })
  it('allows registration only when explicitly enabled',()=>{
    expect(registrationRedirect('/register',true)).toBeUndefined()
  })
})
