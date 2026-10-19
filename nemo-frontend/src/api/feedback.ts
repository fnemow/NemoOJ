import { request } from './client'
import type { Feedback, FeedbackStatus, FeedbackType, Id, PageResult } from './types'

export interface FeedbackQuery {
  current:number; pageSize:number; status?:FeedbackStatus|undefined; type?:FeedbackType|undefined; problemNo?:number|undefined; userId?:Id|undefined
}
export function addFeedback(body:{type:FeedbackType;problemNo?:number|undefined;title:string;content:string}):Promise<Id>{
  return request('/question/feedback/add',{method:'POST',body,mutation:true})
}
export function myFeedback(body:FeedbackQuery):Promise<PageResult<Feedback>>{
  return request('/question/feedback/my/list/page',{method:'POST',body})
}
export function getFeedback(id:Id):Promise<Feedback>{return request(`/question/feedback/get?id=${encodeURIComponent(id)}`)}
export function adminFeedback(body:FeedbackQuery):Promise<PageResult<Feedback>>{
  return request('/question/admin/feedback/list/page',{method:'POST',body})
}
export function adminFeedbackDetail(id:Id):Promise<Feedback>{return request(`/question/admin/feedback/detail?id=${encodeURIComponent(id)}`)}
export function handleFeedback(body:{id:Id;status:Exclude<FeedbackStatus,'PENDING'>;adminReply:string;expectedRowVersion:number}):Promise<Feedback>{
  return request('/question/admin/feedback/handle',{method:'POST',body,mutation:true})
}
