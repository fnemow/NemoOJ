export interface NormalizedStatus { text:string; kind:'wait'|'run'|'ok'|'fail'; terminal:boolean }
const labels:Record<string,string>={accepted:'通过',compile_error:'编译错误',runtime_error:'运行错误',wrong_answer:'答案错误',time_limit_exceeded:'时间超限',memory_limit_exceeded:'内存超限',output_limit_exceeded:'输出超限',presentation_error:'格式错误',dangerous_operation:'不允许的操作',language_not_supported:'语言不可用',system_error:'判题服务异常'}
export function normalizeStatus(status:number,verdict?:string|null):NormalizedStatus {
  if(status===0)return{text:'等待判题',kind:'wait',terminal:false}
  if(status===1)return{text:'判题中',kind:'run',terminal:false}
  const key=(verdict??'').toLowerCase().replaceAll(' ','_')
  if(status===2&&(key==='accepted'||key===''))return{text:'通过',kind:'ok',terminal:true}
  return{text:labels[key]??'结果异常或未通过',kind:'fail',terminal:true}
}
