<script setup lang="ts">
import { computed,ref,watch } from 'vue'
import { request } from '@/api/client'
import { readableError } from '@/api/errors'
import type { Problem,ExecutionMode,LanguageId } from '@/api/types'
import LoadingSpinner from './LoadingSpinner.vue'
const props=defineProps<{problem:Problem;language:LanguageId;mode:ExecutionMode;code:string;disabled?:boolean}>()
const open=ref(false),custom=ref(false),sampleIndex=ref(0),input=ref(''),busy=ref(false),error=ref('')
interface Result { verdict:string;output:string;diagnostic:string|null;executedTime:number|null;executedMemory:number|null;exitCode:number|null }
const result=ref<Result|null>(null)
const samples=computed(()=>props.mode==='FUNCTION'?props.problem.functionSamples??[]:props.problem.samples)
const inputPlaceholder=computed(()=>props.mode==='FUNCTION'?(samples.value[0]?.input??JSON.stringify(Object.fromEntries(props.problem.problemSpec?.parameters.map(p=>[p.name,p.type.includes('[')?[1,2,3]:p.type==='string'?'example':p.type==='boolean'?true:1])??[]))):'请输入程序的标准输入')
watch([custom,sampleIndex,input],()=>{result.value=null;error.value=''})
let sequence=0
watch(()=>[props.problem.id,props.problem.problemVersion,props.language,props.mode],()=>{sequence++;result.value=null;error.value='';sampleIndex.value=0;input.value='';custom.value=!samples.value.length})
function show(){open.value=true;custom.value=!samples.value.length}
async function run(){
  if(props.disabled||busy.value)return
  const seq=++sequence;busy.value=true;error.value='';result.value=null
  const body={questionId:props.problem.id,problemVersion:props.problem.problemVersion,language:props.language,executionMode:props.mode,code:props.code,
    ...(custom.value?{input:input.value}:{sampleIndex:sampleIndex.value})}
  try{const value=await request<Result>('/question/try_run',{method:'POST',body,mutation:true,timeoutMs:60000});if(seq===sequence)result.value=value}
  catch(e){if(seq===sequence)error.value=readableError(e)}
  finally{busy.value=false}
}
</script>
<template>
  <el-button :disabled="disabled" @click="show">试运行</el-button>
  <el-drawer v-model="open" title="试运行代码" size="min(560px,100vw)" append-to-body>
    <div class="try-run">
      <p class="muted">仅执行一次输入，不计入提交记录和通过率。每人每分钟最多 3 次；正式判题繁忙时请稍后再试。</p>
      <el-radio-group v-model="custom" :disabled="busy"><el-radio-button :value="false" :disabled="!samples.length">公开样例</el-radio-button><el-radio-button :value="true">自定义输入</el-radio-button></el-radio-group>
      <el-select v-if="!custom" v-model="sampleIndex" aria-label="选择公开样例" :disabled="busy"><el-option v-for="(_,index) in samples" :key="index" :value="index" :label="`样例 ${index+1}`" /></el-select>
      <p v-if="mode==='FUNCTION'">核心代码输入使用 JSON 对象，字段名与函数参数名一致。例如 <code>{{inputPlaceholder}}</code>。</p>
      <label v-if="custom">输入（最多 64 KiB）<textarea v-model="input" class="mono" :disabled="busy" spellcheck="false" aria-label="自定义输入" :placeholder="inputPlaceholder" /></label>
      <pre v-else>{{samples[sampleIndex]?.input}}</pre>
      <el-button type="primary" :disabled="disabled||busy||(!custom&&!samples.length)" @click="run">运行当前代码</el-button>
      <p v-if="busy" class="progress" role="status"><LoadingSpinner />正在编译与运行，请稍候…</p>
      <p v-if="error" role="alert">{{error}}</p>
      <section v-if="result" aria-live="polite"><h3>{{result.verdict==='Completed'?'运行完成':result.verdict}}</h3><p>耗时 {{result.executedTime??'—'}} ms · 内存 {{result.executedMemory==null?'—':(result.executedMemory/1024).toFixed(1)}} MiB</p><h4>实际输出</h4><pre>{{result.output||'（无输出）'}}</pre><template v-if="!custom"><h4>样例期望输出</h4><pre>{{samples[sampleIndex]?.output}}</pre></template><template v-if="result.diagnostic"><h4>诊断信息</h4><pre>{{result.diagnostic}}</pre></template><p class="muted">试运行成功只表示程序运行结束，不能代替正式判题。</p></section>
    </div>
  </el-drawer>
</template>
<style scoped>
.try-run{display:grid;gap:16px;line-height:1.6}.try-run p{margin:0}.try-run pre,.try-run textarea{background:var(--oj-bg);color:var(--oj-text);border:1px solid var(--oj-border);border-radius:8px;padding:12px;white-space:pre-wrap;overflow-wrap:anywhere;max-height:300px;overflow:auto}.try-run textarea{display:block;width:100%;min-height:160px;margin-top:8px;resize:vertical}.progress{display:flex;align-items:center;gap:10px}.muted{color:var(--oj-text-muted);font-size:12px}
</style>
