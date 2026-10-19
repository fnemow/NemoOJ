import {beforeEach,describe,expect,it,vi} from 'vitest'
const client=vi.hoisted(()=>({request:vi.fn()}))
vi.mock('./client',()=>client)
import {listPrivateBatches} from './ai'

describe('private batch pagination wire contract',()=>{
  beforeEach(()=>client.request.mockReset())
  it.each(['0','2',2])('accepts Java Long totals %s without converting IDs',async total=>{
    const records=[{id:'9223372036854775807',items:[]}]
    client.request.mockResolvedValue({current:1,pageSize:1,total,records})
    const page=await listPrivateBatches(1,1,undefined,'HARD','READY')
    expect(typeof page.total).toBe('number')
    expect(page.total).toBe(Number(total))
    expect(page.records).toBe(records)
    expect(page.records[0]?.id).toBe('9223372036854775807')
    expect(client.request).toHaveBeenCalledWith('/ai/private-problem-batches?current=1&pageSize=1&difficulty=HARD&status=READY',{signal:undefined})
  })
})
