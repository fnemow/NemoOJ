import { describe,expect,it } from 'vitest'
import { normalizeStatus } from './status'
describe('normalizeStatus',()=>{
  it('keeps waiting and running non-terminal',()=>{expect(normalizeStatus(0).terminal).toBe(false);expect(normalizeStatus(1).text).toBe('判题中')})
  it('maps stable verdicts',()=>{expect(normalizeStatus(2,'accepted')).toMatchObject({kind:'ok',terminal:true});expect(normalizeStatus(3,'time_limit_exceeded').text).toBe('时间超限')})
  it('does not treat unknown terminal states as accepted',()=>{expect(normalizeStatus(2,'unexpected').kind).toBe('fail')})
})
