<script setup lang="ts">
import { computed } from 'vue';import MarkdownIt from 'markdown-it';import DOMPurify from 'dompurify'
const props=defineProps<{ source:string }>();const md=new MarkdownIt({html:false,linkify:true,typographer:false});
const html=computed(()=>DOMPurify.sanitize(md.render(props.source??''),{USE_PROFILES:{html:true},FORBID_TAGS:['style','iframe','svg','math']}))
</script>
<template><div class="markdown" v-html="html" /></template>
<style>.markdown{font-size:15px;line-height:1.78;color:#cbd7e6;word-break:break-word}.markdown h1,.markdown h2,.markdown h3{color:var(--oj-text);line-height:1.35;margin:1.5em 0 .6em}.markdown h1{font-size:1.65em}.markdown h2{font-size:1.32em;border-bottom:1px solid var(--oj-border);padding-bottom:.35em}.markdown pre{background:#0b121d;border:1px solid var(--oj-border);padding:14px;border-radius:7px;overflow:auto}.markdown code{font-family:"SFMono-Regular",Consolas,monospace;background:#0b121d;color:#b9d2ff;padding:.15em .35em;border-radius:4px}.markdown pre code{padding:0}.markdown img{max-width:100%;height:auto}.markdown a{overflow-wrap:anywhere}</style>
