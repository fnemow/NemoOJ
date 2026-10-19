export type Id = string
export type LanguageId = 'java' | 'python' | 'cpp'
export type ExecutionMode = 'ACM' | 'FUNCTION'
export type SubmissionStatus = 0 | 1 | 2 | 3

export interface ApiResponse<T> { code: number; data: T; message: string; errorKey?: string | null }
export interface PageResult<T> { records: T[]; total: number; current: number; size: number }
export interface User {
  id: Id; userAccount?: string; userName: string | null; userAvatar: string | null
  userProfile: string | null; userRole: 'user' | 'admin' | 'ban'; createTime: string; updateTime?: string
}
export interface JudgeConfig { timeLimit: number; memoryLimit: number; outputLimitBytes: number }
export interface JudgeCase { input: string; output: string; isHidden: boolean }
export interface ParameterSpec { name: string; type: ValueType }
export type ValueType = 'int'|'long'|'double'|'boolean'|'string'|'int[]'|'long[]'|'double[]'|'boolean[]'|'string[]'|'int[][]'|'list<int>'|'list_node<int>'|'tree_node<int>'|`node<${string}>`
export interface CustomStructureSpec { name:string;fields:Array<{name:string;type:'int'|'long'|'double'|'boolean'|'string'|'ref'|'ref[]'}> }
export interface ProblemSpec {
  mode: 'function'; className: 'Solution'; methodName: string; parameters: ParameterSpec[]
  returnType: ValueType|'void'; resultSource: string
  comparator: { type: 'EXACT'|'TEXT'|'FLOAT'|'UNORDERED_LIST'|'MULTISET'; epsilon?: number | null }
  harnessVersion: number; judgeCases?: JudgeCase[]
  dataStructures?:CustomStructureSpec[]
}
export interface Problem {
  id: Id; problemNo: number | null; title: string; content: string; tagList: string[]; submitNum: number; acceptedNum: number
  problemSpec: ProblemSpec | null; judgeConfig: JudgeConfig | null
  samples: Array<{ input: string; output: string; explanation?: string | null }>
  functionSamples?: Array<{ input: string; output: string }>
  problemVersion: number; isPublished: boolean; createTime: string; updateTime: string
}
export interface LanguageCapability {
  id: LanguageId; displayName: string; runtimeId: string; enabled: boolean; disabledReason?: string
  modes: ExecutionMode[]
  maxCodeBytes: number; effectiveLimits: { timeLimitMs: number; memoryLimitKB: number; outputLimitBytes: number }
}
export interface ProblemCapabilities {
  questionId: Id; problemVersion: number; mode: ExecutionMode; capabilitiesVersion: string
  availableModes: ExecutionMode[]; expiresAt: string; recommendedLanguage: LanguageId | null; languages: LanguageCapability[]
}
export interface CodeTemplate {
  questionId: Id; problemVersion: number; language: LanguageId; mode: ExecutionMode
  fileName: string; templateVersion: string; code: string; instructions: string
}
export interface JudgeInfo {
  message?: string | null; executedTime?: number | null; executedMemory?: number | null
  runtimeId?: string | null; policyVersion?: string | null
}
export interface Submission {
  problemSource?: 'PUBLIC' | 'PRIVATE' | null

  id: Id; language: LanguageId; code: string | null; judgeInfo: JudgeInfo | null; status: SubmissionStatus
  executionMode: ExecutionMode | null
  questionId: Id; problemNo: number | null; questionTitle: string | null; userId: Id; problemVersion: number | null; verdict: string | null
  runtime: { runtimeId: string; displayName: string } | null
  effectiveLimits: { timeLimitMs: number; memoryLimitKB: number; outputLimitBytes: number } | null
  diagnostics: { phase: 'compile'|'run'|'system'; message: string; truncated: boolean } | null
  createTime: string; updateTime: string
}
export interface AdminQuestion {
  id: Id; problemNo: number; title: string; content: string; tags: string[]; answer: string | null
  judgeCase: JudgeCase[]; judgeConfig: JudgeConfig; problemSpec: ProblemSpec | null
  problemVersion: number; isPublished: boolean; rowVersion: number; sourceQuestionId: Id | null
  lifecycleStatus: ProblemLifecycleStatus; lifecycleVersion: number
}
export interface ProblemFormValue {
  title: string; content: string; tags: string[]; answer: string
  judgeCase: JudgeCase[]; judgeConfig: JudgeConfig; problemSpec: ProblemSpec | null
}
export type ProblemLifecycleStatus = 'DRAFT'|'ONLINE'|'OFFLINE'
export interface AdminProblemSummary {
  problemNo: number; rootQuestionId: Id; currentQuestionId: Id|null; title: string
  currentVersion: number|null; currentRowVersion:number|null; status: ProblemLifecycleStatus; lifecycleVersion: number
  offlineReason: string|null; draftId: Id|null; draftVersion: number|null; updateTime: string
}
export interface ProblemIdentity {
  problemNo: number; questionId: Id; currentQuestionId: Id|null; status: ProblemLifecycleStatus
  lifecycleVersion: number; lastOperatorId: Id|null; offlineReason: string|null; updateTime: string
}
export type FeedbackType = 'PROBLEM'|'BUG'|'SUGGESTION'|'OTHER'
export type FeedbackStatus = 'PENDING'|'PROCESSING'|'RESOLVED'
export interface Feedback {
  id: Id; userId: Id; userAccount: string|null; userName: string|null; type: FeedbackType
  problemNo: number|null; title: string; content: string; status: FeedbackStatus
  adminReply: string|null; handledBy: Id|null; handledAt: string|null; rowVersion: number
  agentStatus?: 'NONE'|'AUTO_REPLIED'|'ESCALATED'|'TAKEN_OVER'|null; replySource?: 'ADMIN'|'AI'|null
  createTime: string; updateTime: string
}
