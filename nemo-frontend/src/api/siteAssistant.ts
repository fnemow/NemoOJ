import { request } from './client'
export interface HelpLink { label:string;to:string }
export interface HelpAnswer { kind:'HELP'|'CODE_REDIRECT'|'UNSUPPORTED'|'UNAVAILABLE';text:string;links:HelpLink[] }
export interface HelpGuide { key:string;title:string;text:string;links:HelpLink[] }
export interface HelpTurn { role:'user'|'assistant';text:string }
export interface HelpTaskResult { status:string;answer?:HelpAnswer|null;taskId?:string;errorCode?:string|null }
export function getHelpGuides(signal?:AbortSignal){return request<HelpGuide[]>('/question/assistant/guides',{signal})}
export function askSiteAssistant(message:string,history:HelpTurn[],idempotencyKey:string,signal?:AbortSignal){return request<HelpTaskResult>('/question/assistant/ask',{method:'POST',body:{message,history,idempotencyKey},signal})}
export function getSiteHelpAnswer(id:string,signal?:AbortSignal){return request<HelpTaskResult>(`/question/assistant/answers/${encodeURIComponent(id)}`,{signal})}
