import { computed, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'

/** Expand inside the workspace so the site's navigation stays accessible. */
export function useEditorExpansion(fontSize: Ref<number>, scope: Ref<string>) {
  const editorExpanded=ref(false)
  const editorFontSize=computed(()=>editorExpanded.value ? Math.min(24,Math.max(16,fontSize.value+2)) : fontSize.value)
  function toggleEditor(){editorExpanded.value=!editorExpanded.value}
  function escape(event: KeyboardEvent){
    // Monaco uses Escape for suggestions first; only handle an unconsumed key.
    if(event.key==='Escape'&&!event.defaultPrevented&&!event.isComposing&&editorExpanded.value){
      editorExpanded.value=false
      event.preventDefault()
    }
  }
  watch(scope,()=>{editorExpanded.value=false})
  onMounted(()=>window.addEventListener('keydown',escape))
  onBeforeUnmount(()=>window.removeEventListener('keydown',escape))
  return { editorExpanded, editorFontSize, toggleEditor }
}
