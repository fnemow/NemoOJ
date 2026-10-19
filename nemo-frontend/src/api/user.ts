import { clearCsrf, request } from './client'
import type { Id, PageResult, User } from './types'

export async function getSession(signal?: AbortSignal): Promise<User> { return request('/user/get/login', { signal }) }
export async function login(body: { userAccount: string; userPassword: string }): Promise<User> {
  const user = await request<User>('/user/login', { method: 'POST', body, mutation: true }); clearCsrf(); return user
}
export async function register(body: { userAccount: string; userPassword: string; checkPassword: string; invitationCode: string }): Promise<Id> {
  return request('/user/register', { method: 'POST', body, mutation: true })
}

export interface RegistrationInvitation {
  id: Id; note: string; createdBy: Id|null; expiresAt: string; usedBy: Id|null
  usedAt: string|null; createTime: string; status: 'AVAILABLE'|'USED'|'REVOKED'|'EXPIRED'
}
export function listInvitations(current: number) {
  return request<PageResult<RegistrationInvitation>>(`/user/invitations/admin?current=${current}&pageSize=20`)
}
export function createInvitation(body: { validityHours: number; note: string }) {
  return request<{ code: string; invitation: RegistrationInvitation }>('/user/invitations/admin', { method: 'POST', body, mutation: true })
}
export function revokeInvitation(id: Id) {
  return request<boolean>(`/user/invitations/admin/${encodeURIComponent(id)}/revoke`, { method: 'POST', mutation: true })
}
export async function logout(): Promise<boolean> { const result = await request<boolean>('/user/logout', { method: 'POST', mutation: true }); clearCsrf(); return result }
export async function updateProfile(body: { userName: string; userProfile: string }): Promise<boolean> {
  return request('/user/update/my', { method: 'POST', body, mutation: true })
}
export async function listUsers(body: Record<string, unknown>): Promise<PageResult<User>> {
  return request('/user/list/page/vo', { method: 'POST', body })
}
export async function setUserBanned(id: Id, banned: boolean): Promise<boolean> {
  return request('/user/update', { method: 'POST', body: { id, userRole: banned ? 'ban' : 'user' }, mutation: true })
}

export interface UserMembership { userId: string; tier: 'FREE'|'VIP'; vipActive: boolean; validUntil: string|null; rowVersion: number|string }
export function getUserMemberships(ids: string[], signal?: AbortSignal) {
  return request<UserMembership[]>(`/user/membership/admin?userIds=${ids.map(encodeURIComponent).join(',')}`, { signal })
}
export function setUserMembership(id: string, body: { tier: 'FREE'|'VIP'; validUntil: string|null; expectedVersion: number|string; reason: string }) {
  return request<boolean>(`/user/membership/admin/${encodeURIComponent(id)}`, { method: 'POST', body, mutation: true })
}
