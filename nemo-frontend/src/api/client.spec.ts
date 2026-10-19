import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { clearCsrf, request } from './client'

const success = (data: unknown) => new Response(JSON.stringify({ code: 0, data }), { status: 200 })
const invalid = () => new Response(JSON.stringify({ code: 40300, data: null, errorKey: 'CSRF_INVALID', message: 'CSRF token invalid' }), { status: 403 })

describe('expired CSRF recovery', () => {
  const fetchMock = vi.fn()
  beforeEach(() => { clearCsrf(); fetchMock.mockReset(); vi.stubGlobal('fetch', fetchMock) })
  afterEach(() => { vi.unstubAllGlobals() })
  it('refreshes a rejected token once and preserves mutation body', async () => {
    fetchMock.mockResolvedValueOnce(success({ token: 'old', headerName: 'X-CSRF-Token' }))
      .mockResolvedValueOnce(invalid())
      .mockResolvedValueOnce(success({ token: 'new', headerName: 'X-CSRF-Token' }))
      .mockResolvedValueOnce(success('ok'))
    const body = { userAccount: 'test-user', userPassword: 'test-password' }
    expect(await request('/user/login', { method: 'POST', mutation: true, body })).toBe('ok')
    expect(fetchMock).toHaveBeenCalledTimes(4)
    const first = fetchMock.mock.calls[1]?.[1] as RequestInit
    const retried = fetchMock.mock.calls[3]?.[1] as RequestInit
    expect(first.body).toBe(retried.body)
    expect(new Headers(retried.headers).get('X-CSRF-Token')).toBe('new')
  })
  it('stops after a second CSRF rejection', async () => {
    fetchMock.mockResolvedValueOnce(success({ token: 'old', headerName: 'X-CSRF-Token' })).mockResolvedValueOnce(invalid())
      .mockResolvedValueOnce(success({ token: 'new', headerName: 'X-CSRF-Token' })).mockResolvedValueOnce(invalid())
    await expect(request('/user/login', { method: 'POST', mutation: true })).rejects.toMatchObject({ errorKey: 'CSRF_INVALID' })
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })
  it('does not repeat mutations after network failure or unrelated forbidden response', async () => {
    fetchMock.mockResolvedValueOnce(success({ token: 'old', headerName: 'X-CSRF-Token' }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ code: 40300, data: null, errorKey: 'NO_PERMISSION' }), { status: 403 }))
    await expect(request('/user/update', { method: 'POST', mutation: true })).rejects.toMatchObject({ errorKey: 'NO_PERMISSION' })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    fetchMock.mockReset(); fetchMock.mockRejectedValueOnce(new TypeError('network'))
    await expect(request('/user/update', { method: 'POST', mutation: true })).rejects.toMatchObject({ kind: 'network' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
  it('does not restore an obsolete token after login clears an in-flight cache', async () => {
    let finishOldToken!: (response: Response) => void
    fetchMock.mockImplementationOnce(() => new Promise<Response>(resolve => { finishOldToken = resolve }))
    const oldRequest = request('/user/update', { method: 'POST', mutation: true })
    clearCsrf()
    fetchMock.mockResolvedValueOnce(success({ token: 'new', headerName: 'X-CSRF-Token' }))
      .mockResolvedValueOnce(success('new-result'))
    await request('/user/update', { method: 'POST', mutation: true })
    fetchMock.mockResolvedValueOnce(success('old-result'))
    finishOldToken(success({ token: 'old', headerName: 'X-CSRF-Token' }))
    await oldRequest
    fetchMock.mockResolvedValueOnce(success('next-result'))
    await request('/user/update', { method: 'POST', mutation: true })
    expect(fetchMock).toHaveBeenCalledTimes(5)
    const next = fetchMock.mock.calls[4]?.[1] as RequestInit
    expect(new Headers(next.headers).get('X-CSRF-Token')).toBe('new')
  })
})
