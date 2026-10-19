import { request } from './client'
import type { AdminProblemSummary, AdminQuestion, CodeTemplate, ExecutionMode, Id, LanguageId, PageResult, Problem, ProblemCapabilities, ProblemFormValue, ProblemIdentity, ProblemLifecycleStatus } from './types'

export interface ProblemFilters { title?: string | undefined; problemNo?: number | undefined; tags?: string[] | undefined; current: number; pageSize: 10|20; sortField?: string; sortOrder?: string }
export function listProblems(filters: ProblemFilters, signal?: AbortSignal): Promise<PageResult<Problem>> {
  return request('/question/list/page/vo', { method: 'POST', body: filters, signal })
}
export function getProblem(id: Id, signal?: AbortSignal): Promise<Problem> {
  const path = /^\d{1,9}$/.test(id) ? `/question/get/vo/by-number?problemNo=${encodeURIComponent(id)}` : `/question/get/vo?id=${encodeURIComponent(id)}`
  return request(path, { signal })
}
export function getProblemByNumber(problemNo: number, signal?: AbortSignal): Promise<Problem> { return request(`/question/get/vo/by-number?problemNo=${encodeURIComponent(problemNo)}`, { signal }) }
export function getCapabilities(id: Id, signal?: AbortSignal): Promise<ProblemCapabilities> { return request(`/question/languages?questionId=${encodeURIComponent(id)}`, { signal }) }
export function getTemplate(id: Id, language: LanguageId, version: number, executionMode: ExecutionMode, signal?: AbortSignal): Promise<CodeTemplate> {
  return request(`/question/code_template?questionId=${encodeURIComponent(id)}&language=${language}&problemVersion=${version}&executionMode=${executionMode}`, { signal })
}
export function adminQuestions(body: { current:number; pageSize:number; title?:string|undefined; problemNo?:number|undefined; status?:ProblemLifecycleStatus|undefined }): Promise<PageResult<AdminProblemSummary>> { return request('/question/admin/problems/list/page', { method: 'POST', body }) }
export function adminDetail(id: Id): Promise<AdminQuestion> { return request(`/question/admin/detail?id=${encodeURIComponent(id)}`) }
export function addQuestion(body: ProblemFormValue): Promise<Id> { return request('/question/add', { method: 'POST', body, mutation: true }) }
export function updateQuestion(id: Id, expectedRowVersion: number, body: ProblemFormValue): Promise<boolean> { return request('/question/update', { method: 'POST', body: { id, expectedRowVersion, ...body }, mutation: true }) }
export function previewQuestion(body: ProblemFormValue): Promise<{ valid: boolean; templates: Array<{ language: LanguageId; fileName: string; code: string }> }> { return request('/question/admin/preview', { method: 'POST', body, mutation: true }) }
export function publishQuestion(id: Id, expectedRowVersion: number): Promise<{ id: Id; problemNo:number; problemVersion: number; published: boolean; status:ProblemLifecycleStatus; rowVersion: number; lifecycleVersion:number }> { return request('/question/admin/publish', { method: 'POST', body: { id, expectedRowVersion }, mutation: true }) }
export function newQuestionVersion(id: Id, expectedRowVersion: number): Promise<Id> { return request('/question/admin/new_version', { method: 'POST', body: { id, expectedRowVersion }, mutation: true }) }
export function takeProblemOffline(problemNo:number,expectedLifecycleVersion:number,reason:string):Promise<ProblemIdentity>{return request('/question/admin/offline',{method:'POST',body:{problemNo,expectedLifecycleVersion,reason},mutation:true})}
export function putProblemOnline(problemNo:number,expectedLifecycleVersion:number):Promise<ProblemIdentity>{return request('/question/admin/online',{method:'POST',body:{problemNo,expectedLifecycleVersion},mutation:true})}
