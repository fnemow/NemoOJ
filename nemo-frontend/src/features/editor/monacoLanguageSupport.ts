import type * as Monaco from 'monaco-editor/esm/vs/editor/editor.api'
import type { editor as MonacoEditor } from 'monaco-editor/esm/vs/editor/editor.api'
import { getLocalCompletions,isCodePosition,memberReceiverAt,type EditorLanguageContext,type LocalCompletionKind } from './languageSupport'

type MonacoApi=typeof Monaco
const registered=new WeakSet<object>()
const contexts=new Map<string,EditorLanguageContext>()

export const DARK_THEME='nemooj-dark'
export const LIGHT_THEME='nemooj-light'

export function registerMonacoLanguageSupport(monaco:MonacoApi):boolean{
  if(registered.has(monaco))return false
  registered.add(monaco)
  defineThemes(monaco)
  for(const language of ['java','python','cpp'] as const){
    monaco.languages.registerCompletionItemProvider(language,{
      triggerCharacters:['.'],
      provideCompletionItems(model,position){
        const context=contexts.get(model.uri.toString())
        if(!context||context.readOnly)return {suggestions:[]}
        const source=model.getValue(),offset=model.getOffsetAt(position)
        if(!isCodePosition(source,context.language,offset))return {suggestions:[]}
        const receiver=memberReceiverAt(source,offset),word=model.getWordUntilPosition(position)
        const range=new monaco.Range(position.lineNumber,word.startColumn,position.lineNumber,word.endColumn)
        return {suggestions:getLocalCompletions(context,source,receiver).map(item=>({
          label:item.label,insertText:item.insertText,detail:item.detail,sortText:item.sortText,range,
          kind:completionKind(monaco,item.kind),
          ...(item.snippet?{insertTextRules:monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet}:{})
        }))}
      }
    })
  }
  return true
}

export function bindEditorModel(model:MonacoEditor.ITextModel,context:EditorLanguageContext){contexts.set(model.uri.toString(),context)}
export function unbindEditorModel(model:MonacoEditor.ITextModel){contexts.delete(model.uri.toString())}

function completionKind(monaco:MonacoApi,kind:LocalCompletionKind):Monaco.languages.CompletionItemKind{
  const kinds=monaco.languages.CompletionItemKind
  return {keyword:kinds.Keyword,snippet:kinds.Snippet,function:kinds.Function,method:kinds.Method,variable:kinds.Variable,class:kinds.Class,constant:kinds.Constant}[kind]
}

function defineThemes(monaco:MonacoApi){
  const rules=[
    {token:'comment',foreground:'6B7280',fontStyle:'italic'},
    {token:'keyword',foreground:'C586C0'},
    {token:'string',foreground:'98C379'},
    {token:'number',foreground:'E5B567'},
    {token:'type',foreground:'56B6C2'},
    {token:'type.identifier',foreground:'56B6C2'},
    {token:'identifier',foreground:'D4D4D4'},
    {token:'function',foreground:'DCDCAA'},
    {token:'delimiter',foreground:'ABB2BF'}
  ]
  monaco.editor.defineTheme(DARK_THEME,{base:'vs-dark',inherit:true,rules,colors:{'editor.background':'#1E1E1E','editor.foreground':'#D4D4D4','editorLineNumber.foreground':'#666666','editorLineNumber.activeForeground':'#D4D4D4','editorCursor.foreground':'#7AA2F7','editor.selectionBackground':'#264F78','editorBracketHighlight.foreground1':'#7AA2F7','editorBracketHighlight.foreground2':'#C586C0','editorBracketHighlight.foreground3':'#56B6C2','editorSuggestWidget.background':'#171A21','editorSuggestWidget.border':'#343A46','editorSuggestWidget.selectedBackground':'#263750'}})
  monaco.editor.defineTheme(LIGHT_THEME,{base:'vs',inherit:true,rules:[{token:'comment',foreground:'6A737D',fontStyle:'italic'},{token:'keyword',foreground:'8A2BE2'},{token:'string',foreground:'287A3E'},{token:'number',foreground:'A45A00'},{token:'type',foreground:'007C91'},{token:'type.identifier',foreground:'007C91'},{token:'identifier',foreground:'24292F'},{token:'function',foreground:'8A6116'},{token:'delimiter',foreground:'4B5563'}],colors:{'editor.background':'#F7F7F8','editor.foreground':'#24292F','editorLineNumber.foreground':'#8C959F','editorLineNumber.activeForeground':'#24292F','editorCursor.foreground':'#315DAD','editor.selectionBackground':'#B6D7FF','editorSuggestWidget.background':'#FFFFFF','editorSuggestWidget.border':'#CDD5DF','editorSuggestWidget.selectedBackground':'#DCEBFF'}})
}

