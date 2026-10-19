import { request } from './client'

export interface AiEntitlements {
  tier: 'FREE' | 'VIP'; vipActive: boolean; analysisEnabled: boolean; planEnabled: boolean
  privateProblemsEnabled?: boolean; feedbackAgentEnabled?: boolean
  usedToday: number; dailyLimit: number | null; remainingToday: number | null; businessDate: string
}
export interface AiTask {
  taskId: string; type: string; status: 'QUEUED'|'RUNNING'|'WAITING_REVIEW'|'SUCCEEDED'|'FAILED'|'CANCELLED'
  stage?: string | null; errorCode?: string | null; message?: string | null; resourceId?: string | null
  createTime?: string; completedAt?: string | null
}
export interface AiAnalysis {
  id: string; verdict: string; modelId: string; promptVersion: string; createTime: string
  report: {
    verdictEvidence: Record<string, unknown>; likelyCause: string
    locations: Array<{ line?: number | null; symbol: string; explanation: string }>
    suggestions: Array<{ title: string; detail: string; impact?: string }>
    complexity: { time: string; space: string; explanation: string }; uncertainty: string
  }
}
export interface AdminAiProposal {
  id:string;taskId:string;problemNo:number;baseProblemVersion:number;baseLifecycleVersion:number;status:string;rowVersion:number
  currentTitle?:string|null;reviewNote?:string|null;approvedDraftId?:number|null;createTime?:string
  proposalJson?:{title:string;content:string;summary:string;uncertainty:string;scope:'EDITORIAL_ONLY';baseRowVersion:number}
  diffMarkdown?:string;evidenceJson?:string[]|string;validationJson?:Record<string,unknown>|string
}
export interface AdminAiProposalPage {current:number;pageSize:number;total:number;records:AdminAiProposal[]}
export function getAdminAiProposals(query:{status:string;current:number;pageSize:number;problemNo?:number|undefined},signal?:AbortSignal){
  const params=new URLSearchParams({status:query.status,current:String(query.current),pageSize:String(query.pageSize)})
  if(query.problemNo)params.set('problemNo',String(query.problemNo))
  return request<AdminAiProposalPage>(`/admin/ai/proposals?${params}`,{signal})
}
export function getAdminAiProposal(id:string,signal?:AbortSignal){return request<AdminAiProposal>(`/admin/ai/proposals/${encodeURIComponent(id)}`,{signal})}
export function requestAdminAiInspection(problemNo:number,idempotencyKey:string){return request<{taskId:string;problemNo:number;status:string}>('/admin/ai/inspections',{method:'POST',body:{problemNo,idempotencyKey},mutation:true})}
export function decideAdminAiProposal(id:string,body:{decision:'APPROVE'|'REJECT';expectedProposalVersion:number;expectedLifecycleVersion:number;note:string}){
  return request<{status:string;draftQuestionId?:number}>(`/admin/ai/proposals/${encodeURIComponent(id)}/decision`,{method:'POST',body,mutation:true})
}
export interface StudyPlanItem {
  id: number; dayNumber: number; orderNumber: number; source: 'PUBLIC'|'PRIVATE'; problemNo: number|null
  privateProblemId: string|null; title: string; reason: string; status: 'TODO'|'DONE'|'UNAVAILABLE'
  completedBySubmitId?: string|null; completedAt?: string|null
}
export interface StudyPlan {
  id: string; title: string; goal: string; level: string; tags: string[]; days: number; minutesPerDay: number
  sourceMode: 'CATALOG'|'MIXED'; status: string; planVersion: number; items: StudyPlanItem[]; completedCount: number
}
export interface PrivateProblem {
  id: string; title: string; tags: string[]; difficulty: 'EASY'|'MEDIUM'|'HARD'; status: string
  versionId?: string|null; rejectReason?: string|null
  currentVersion?: { id: string; version: number; content: string; samples: Array<{input:string;output:string}>;functionSamples?:Array<{input:string;output:string}>;
    availableModes: Array<'ACM'|'FUNCTION'>; templates: Array<{language:'java'|'python'|'cpp';mode?:'ACM'|'FUNCTION';fileName:string;code:string}>;
    problemSpec?: import('./types').ProblemSpec|null }
}
export function listStudyPlans(signal?:AbortSignal){return request<StudyPlan[]>('/ai/plans',{signal})}
export function getStudyPlan(id:string,signal?:AbortSignal){return request<StudyPlan>(`/ai/plans/${encodeURIComponent(id)}`,{signal})}
export function createStudyPlan(body:{goal?:string;inputVersion?:'CATALOG_V2';allowReview?:boolean;level:string;tags:string[];days:number;minutesPerDay:number;sourceMode:'CATALOG'|'MIXED';idempotencyKey:string}){return request<AiTask&{planId:string}>('/ai/plans',{method:'POST',body,mutation:true})}
export interface PlanTopic {tag:string;total:number;accepted:number;available:number}
export function getPlanTopics(allowReview:boolean,signal?:AbortSignal){return request<PlanTopic[]>(`/ai/plans/topics?allowReview=${allowReview}`,{signal})}
export interface PrivateBatchItem {
  difficulty:'EASY'|'MEDIUM'|'HARD';privateProblemId:string;taskId:string;status:string;stage?:string|null
  title:string;problemStatus:string;message?:string|null;repairCount:number;attempt:number;completed:boolean
  validatedCombinations?:number
}
export interface PrivateBatch {id:string;goal:string;rowVersion:number;status:string;items:PrivateBatchItem[];createTime:string}
export function createPrivateBatch(goal:string,idempotencyKey:string){return request<PrivateBatch>('/ai/private-problem-batches',{method:'POST',body:{goal,idempotencyKey},mutation:true})}
export async function listPrivateBatches(current:number,pageSize=10,signal?:AbortSignal,difficulty='',status=''){
  const query=new URLSearchParams({current:String(current),pageSize:String(pageSize),difficulty,status})
  const data=await request<{current:number;pageSize:number;total:number|string;records:PrivateBatch[]}>(`/ai/private-problem-batches?${query}`,{signal})
  // The Java Long serializer preserves IDs as strings; pagination needs a number.
  return {...data,total:Number(data.total)}
}
export function retryPrivateBatchItem(id:string,difficulty:PrivateBatchItem['difficulty'],expectedVersion:number,idempotencyKey:string){return request<PrivateBatch>(`/ai/private-problem-batches/${encodeURIComponent(id)}/items/${difficulty}/retry`,{method:'POST',body:{expectedVersion,idempotencyKey},mutation:true})}
export function adjustStudyPlan(id:string,body:{changeRequest:string;expectedVersion:number;idempotencyKey:string}){return request<AiTask&{planId:string}>(`/ai/plans/${encodeURIComponent(id)}/adjust`,{method:'POST',body,mutation:true})}
export function archiveStudyPlan(id:string,expectedVersion:number){return request<boolean>(`/ai/plans/${encodeURIComponent(id)}/archive`,{method:'POST',body:{expectedVersion},mutation:true})}
export function createPrivateProblem(body:{goal:string;difficulty:'EASY'|'MEDIUM'|'HARD';tags:string[];idempotencyKey:string}){return request<AiTask&{privateProblemId:string}>('/ai/private-problems',{method:'POST',body,mutation:true})}
export function listPrivateProblems(signal?:AbortSignal,legacyOnly=false){return request<PrivateProblem[]>(`/ai/private-problems${legacyOnly?'?legacyOnly=true':''}`,{signal})}
export function getPrivateProblem(id:string,signal?:AbortSignal){return request<PrivateProblem>(`/ai/private-problems/${encodeURIComponent(id)}`,{signal})}
export function submitPrivateProblem(id:string,body:{problemVersion:string;language:'java'|'python'|'cpp';executionMode:'ACM'|'FUNCTION';code:string;clientRequestId:string}){return request<string>(`/ai/private-problems/${encodeURIComponent(id)}/submissions`,{method:'POST',body,mutation:true})}
export function getPrivateProblemSubmissions(id:string,current=1,signal?:AbortSignal){return request<import('./types').PageResult<import('./types').Submission>>(`/ai/private-problems/${encodeURIComponent(id)}/submissions?current=${current}&pageSize=20`,{signal})}
export function getAiEntitlements(signal?: AbortSignal) { return request<AiEntitlements>('/ai/entitlements', { signal }) }
export function requestAnalysis(submissionId: string, idempotencyKey: string) {
  return request<AiTask & { analysisId?: string; reused?: boolean }>('/ai/analyses', {
    method: 'POST', mutation: true, body: { submissionId, idempotencyKey }
  })
}
export function getAiTask(taskId: string, signal?: AbortSignal) { return request<AiTask>(`/ai/tasks/${encodeURIComponent(taskId)}`, { signal }) }
export function getAnalysis(submissionId: string, signal?: AbortSignal) { return request<AiAnalysis>(`/ai/analyses/${encodeURIComponent(submissionId)}`, { signal }) }
