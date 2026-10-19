import { openDB, type DBSchema } from 'idb'
import type { ExecutionMode, Id, LanguageId } from '@/api/types'

export interface Draft { key:string; code:string; templateVersion:string; updatedAt:number; revision:number; backup?:string }
interface DraftDB extends DBSchema { drafts: { key:string; value:Draft } }
let dbPromise: ReturnType<typeof openDB<DraftDB>> | undefined
function getDraftDb(){
  dbPromise ??= openDB<DraftDB>('nemooj-drafts',1,{upgrade(db){db.createObjectStore('drafts',{keyPath:'key'})}})
  return dbPromise
}
const draftChannel=typeof BroadcastChannel==='undefined'?null:new BroadcastChannel('nemooj-drafts-v1')
export function draftKey(userId:Id|'guest',questionId:Id,version:number,language:LanguageId,mode:ExecutionMode,templateVersion:string){return `3:${userId}:${questionId}:${version}:${language}:${mode}:${encodeURIComponent(templateVersion)}`}
export async function readDraft(key:string){return (await getDraftDb()).get('drafts',key)}
export async function writeDraft(input:Omit<Draft,'revision'|'updatedAt'>):Promise<Draft>{const db=await getDraftDb();const tx=db.transaction('drafts','readwrite');const current=await tx.store.get(input.key);const next={...input,updatedAt:Date.now(),revision:(current?.revision??0)+1};await tx.store.put(next);await tx.done;draftChannel?.postMessage({key:next.key,revision:next.revision});return next}
export function listenDrafts(listener:(event:{key:string;revision:number})=>void):()=>void{if(!draftChannel)return()=>{};const handler=(event:MessageEvent<{key:string;revision:number}>)=>listener(event.data);draftChannel.addEventListener('message',handler);return()=>draftChannel.removeEventListener('message',handler)}
export async function backupDraft(key:string,code:string){const current=await readDraft(key);if(current)await writeDraft({...current,backup:code})}
export async function clearUserDrafts(userId:Id){const db=await getDraftDb();const tx=db.transaction('drafts','readwrite');let cursor=await tx.store.openCursor();while(cursor){const key=cursor.key.toString();if(key.startsWith(`1:${userId}:`)||key.startsWith(`2:${userId}:`)||key.startsWith(`3:${userId}:`))await cursor.delete();cursor=await cursor.continue()}await tx.done}
