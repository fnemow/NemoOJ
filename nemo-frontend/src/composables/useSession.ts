import { computed } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ApiError } from '@/api/errors'
import { getSession, logout as logoutApi } from '@/api/user'
import { filingMode } from '@/app/siteMode'

export type SessionState = 'unknown'|'authenticated'|'anonymous'|'unavailable'
export const sessionKey = ['session'] as const

async function sessionQuery(context?: { signal?: AbortSignal }) {
  try { return await getSession(context?.signal) }
  catch (error) {
    if (error instanceof ApiError && error.code === 40100) return null
    throw error
  }
}

export function useSession() {
  const client = useQueryClient()
  const query = useQuery({ queryKey: sessionKey, queryFn: sessionQuery, enabled: !filingMode, retry: false, staleTime: 30_000 })
  const user = computed(()=>filingMode?null:query.data.value)
  const state = computed<SessionState>(() => filingMode?'anonymous':query.isPending.value ? 'unknown' : query.isError.value ? 'unavailable' : query.data.value ? 'authenticated' : 'anonymous')
  async function logout() {
    await logoutApi()
    await client.cancelQueries({ predicate: q => q.queryKey[0] !== 'public' })
    // Keep the observed session query alive so every mounted user/menu updates.
    client.setQueryData(sessionKey, null)
    client.removeQueries({ predicate: q => q.queryKey[0] !== 'public' && q.queryKey[0] !== sessionKey[0] })
  }
  return { ...query, user, state, logout }
}

export { sessionQuery }
