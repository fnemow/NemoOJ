import { test,expect } from '@playwright/test'

const problemSpec={
  mode:'function',className:'Solution',methodName:'solve',
  parameters:[{name:'value',type:'int'}],returnType:'int',resultSource:'return',
  comparator:{type:'EXACT'},harnessVersion:1,
}

test('visitor opens a problem and switches between ACM and core-code mode',async({page})=>{
  let requestedMode=''
  page.on('pageerror',error=>console.error('browser page error',error))
  page.on('console',message=>{if(message.type()==='error')console.error('browser console',message.text())})
  await page.route('**/api/user/get/login',r=>r.fulfill({json:{code:40100,data:null,message:'未登录'}}))
  await page.route('**/api/question/list/page/vo',r=>r.fulfill({json:{code:0,message:'ok',data:{records:[{id:'9007199254740993',title:'两数之和',content:'# 题目',tagList:['数组'],submitNum:10,acceptedNum:7,problemSpec,judgeConfig:{timeLimit:1000,memoryLimit:65536,outputLimitBytes:1048576},samples:[],problemVersion:1,isPublished:true,createTime:'2026-09-19T12:00:00+08:00',updateTime:'2026-09-19T12:00:00+08:00'}],total:1,current:1,size:20}}}))
  await page.route('**/api/question/get/vo?id=*',r=>r.fulfill({json:{code:0,message:'ok',data:{id:'9007199254740993',title:'两数之和',content:'# 题目描述\n求两个整数的和。',tagList:['数组'],submitNum:10,acceptedNum:7,problemSpec,judgeConfig:{timeLimit:1000,memoryLimit:65536,outputLimitBytes:1048576},samples:[{input:'1 2',output:'3'}],problemVersion:1,isPublished:true,createTime:'2026-09-19T12:00:00+08:00',updateTime:'2026-09-19T12:00:00+08:00'}}}))
  await page.route('**/api/question/languages?questionId=*',r=>r.fulfill({json:{code:0,message:'ok',data:{questionId:'9007199254740993',problemVersion:1,mode:'ACM',availableModes:['ACM','FUNCTION'],capabilitiesVersion:'test',expiresAt:'2026-09-19T12:01:00+08:00',recommendedLanguage:'python',languages:[{id:'python',displayName:'Python 3.12',runtimeId:'python-test',enabled:true,modes:['ACM','FUNCTION'],maxCodeBytes:524288,effectiveLimits:{timeLimitMs:3000,memoryLimitKB:65536,outputLimitBytes:1048576}}]}}}))
  await page.route('**/api/question/code_template?*',r=>{
    const mode=new URL(r.request().url()).searchParams.get('executionMode')??'ACM'
    requestedMode=mode
    return r.fulfill({json:{code:0,message:'ok',data:{questionId:'9007199254740993',problemVersion:1,language:'python',mode,fileName:mode==='FUNCTION'?'solution.py':'main.py',templateVersion:`test-${mode}`,code:mode==='FUNCTION'?'class Solution:\n    def solve(self, value):\n        return value\n':'print(sum(map(int, input().split())))',instructions:mode}}})
  })

  await page.goto('/problems')
  await expect(page.getByRole('heading',{name:'题库'})).toBeVisible()
  await expect(page.locator('.sidebar')).toBeVisible()
  await expect(page.getByText('9007199254740993')).toBeVisible()
  await page.getByText('两数之和').click()
  await expect(page).toHaveURL(/\/problems\/9007199254740993/)
  await expect(page.getByRole('heading',{name:'两数之和'})).toBeVisible()
  await expect(page.locator('.toolbar').getByText('Python 3.12').first()).toBeVisible()
  await expect(page.getByRole('switch',{name:'判题模式'})).toBeEnabled()
  await expect.poll(()=>requestedMode).toBe('ACM')
  await page.locator('.mode-switch .el-switch').click()
  await expect.poll(()=>requestedMode).toBe('FUNCTION')
  await expect(page.getByText('函数签名')).toBeVisible()

  const problemBox=await page.locator('.problem-pane').boundingBox()
  const editorBox=await page.locator('.code-pane').boundingBox()
  expect(problemBox).not.toBeNull()
  expect(editorBox).not.toBeNull()
  expect(problemBox!.x).toBeLessThan(editorBox!.x)
})
