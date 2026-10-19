import { ElMessage as elementMessage } from 'element-plus'
import type { MessageHandler } from 'element-plus'

type MessageInput = string | { message: string }
type TextMessage = (message: MessageInput) => MessageHandler

// Element Plus 2.11.4 cannot infer message props with Vue 3.5.22 and
// exactOptionalPropertyTypes (upstream issue #22428). Keep the compatibility
// assertion at this boundary; callers still accept only plain text messages.
export const ElMessage = elementMessage as unknown as {
  success: TextMessage
  warning: TextMessage
  error: TextMessage
  info: TextMessage
}
