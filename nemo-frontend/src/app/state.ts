import { QueryClient } from '@tanstack/vue-query'
import { shouldRetry } from '@/api/client'

export const queryClient = new QueryClient({ defaultOptions: { queries: { retry: shouldRetry, refetchOnWindowFocus: false }, mutations: { retry: false } } })
