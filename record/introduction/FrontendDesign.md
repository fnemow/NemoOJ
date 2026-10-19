# NemoOJ 前端设计与 Agent 实施规范

编制日期：2026-09-17。实施更新：2026-09-19。状态：首版前端已在 `nemo-frontend/` 实现，B01–B06 后端契约已落地；真实部署仍需应用 V7 迁移并完成生产环境联调。

本文面向实施 agent，路径均相对仓库根目录。先阅读 `record/rules.md`、本文件与 [MultiLanguage.md](MultiLanguage.md)，再核对最新源码。文中“现有”来自本次静态源码检查，不代表服务已部署或接口已通过联调；标为“新增/调整”的契约必须先实现，不能当作已有接口调用。

## 1. 已确定的产品范围

目标：完成“浏览题库 → 阅读题目 → 编写代码 → 提交 → 跟踪判题 → 查看历史”的完整流程，并提供管理员维护题目的入口。

用户已确认：Vue 3 + TypeScript + Vite + Element Plus + Monaco Editor；简体中文界面；全站浅色、蓝色强调色；编辑器可独立切换明暗主题；桌面做题优先；包含题库、做题页、提交记录、个人中心、管理后台。

语言支持以服务端实际能力为准，目标为 Java、Python 3.12、C++17，覆盖 ACM 与 FUNCTION。不能因为前端下拉框中有该语言，就宣称后端已支持。界面显示“完整程序模式”“函数模式”，内部标识保留 ACM/FUNCTION。

### 1.1 首版必须完成

- 登录、注册、退出、登录态恢复、个人资料修改。
- 已发布题目列表、标题/标签查询、分页与题目详情。
- 桌面代码编辑、语言切换、模板、草稿保存、正式提交、结果轮询。
- 我的提交列表、提交详情、本人源码只读查看及复制回编辑器。
- 管理后台：题目列表、新建/编辑、样例与隐藏用例、函数规格、资源限制、预览、发布与新版本工作流；用户列表与角色管理。
- 加载、空数据、网络失败、登录失效、无权限、404 和服务能力不可用等完整状态。

### 1.2 后续范围

独立“运行样例/自定义测试”、比赛、排行、讨论、题解社区、收藏、云端草稿、AI 助手、远程调试/LSP、全站深色主题、完整手机 IDE 均不纳入首版。首页直接进入题库；不新增无业务内容的统计大屏或营销页。

正式提交与试运行是不同业务。当前没有独立试运行接口，首版不展示可点击的“运行”按钮；后续有安全受限的异步试运行接口后再启用，不借用正式提交接口伪装试运行。

本文件新增独立前端建设任务，替代旧上线计划中的“不新增前端”范围约束；原后端上线任务仍须完成。

## 2. 技术选型与工程边界

| 层 | 确定方案 | 约束 |
|---|---|---|
| 工程 | 新建 `nemo-frontend/`，Vue 3、TypeScript strict、Vite、npm | 固定实际依赖版本与 package-lock.json；声明匹配 Vite 要求的 Node 版本；CI 用 npm ci |
| 路由 | Vue Router，history 模式，页面懒加载 | 刷新深层路由可访问；API 不能落入 SPA fallback |
| UI | Element Plus 按需引入 + 原生 CSS 变量 | 一套组件库；公共 token 控制颜色、间距和边框 |
| 服务端状态 | TanStack Vue Query | 管题目、提交、用户资料的请求缓存与轮询 |
| 本地偏好 | Pinia | 仅编辑器主题、字号、面板比例等；不复制全部 Query 数据 |
| 请求 | 封装原生 fetch | 统一业务信封、Cookie、取消请求与错误类型；组件不能散写 fetch |
| 编辑器 | Monaco，ESM + Vite 本地 worker | 路由级异步加载；不依赖公网 CDN |
| 内容 | markdown-it + DOMPurify | 禁用原始 HTML，最终 HTML 再净化；公式需要时再以固定、安全配置引入 |
| 测试 | Vitest + Vue Test Utils + Playwright | 单测覆盖规则，浏览器测试覆盖用户流程；禁止用全量快照替代关键断言 |

Vite 构建不替代 TypeScript 检查，必须单独运行 `vue-tsc --noEmit`。[Vue 官方 TypeScript 说明](https://vuejs.org/guide/typescript/overview)

建议目录：

```text
nemo-frontend/
  src/
    app/                 # main、router、providers、路由守卫
    api/                 # client、errors、types、user/question/submission/admin API
    layouts/             # AppLayout、WorkspaceLayout、AdminLayout、AuthLayout
    pages/               # 路由入口，负责组合和路由参数
    features/
      auth/              # 登录/注册、会话状态
      problems/          # 列表、题面、样例
      workspace/         # 编辑器、草稿、模板与提交编排
      submissions/       # 结果、历史、轮询
      profile/           # 个人资料表单
      admin/             # 题目/用例/签名/用户管理
    components/          # 可跨功能复用的展示组件
    composables/         # 少量跨功能组合逻辑
    stores/              # 本地偏好
    styles/              # tokens.css、base.css、Element Plus 覆盖
    mocks/               # 明确开发用 fixtures，不进入生产请求回退
  tests/e2e/
  docs/                  # 联调记录、验收证据与接口变更记录
  .env.example
  README.md
```

组件不直接操作 localStorage/IndexedDB 或拼接请求地址；持久化、接口适配、轮询分别集中封装。接口 DTO 与前端 view model 分离，边界转换集中在 api/adapters 中。不要生成一个巨型全局 store 或由页面单文件承载全部业务。

## 3. 信息架构、路由与权限

| 路由 | 页面/布局 | 权限与行为 |
|---|---|---|
| `/` | 跳转 `/problems` | 所有人 |
| `/problems` | 题库 / AppLayout | 游客可浏览已发布题目 |
| `/problems/:problemId` | 做题页 / WorkspaceLayout | 游客可阅读与本地编辑，提交前登录 |
| `/submissions` | 我的提交 / AppLayout | 登录；后端限定当前用户 |
| `/submissions/:submissionId` | 提交详情 / AppLayout | 首版本人或管理员；无权限不返回源码 |
| `/login`、`/register` | AuthLayout | 登录后回到安全 returnTo 路径 |
| `/me` | 个人中心 / AppLayout | 登录 |
| `/admin/problems` | 题目管理 / AdminLayout | admin |
| `/admin/problems/new` | 新建题目 / AdminLayout | admin |
| `/admin/problems/:problemId/edit` | 编辑/只读已发布版本 | admin |
| `/admin/users` | 用户管理 / AdminLayout | admin |
| `/403`、`/:pathMatch(.*)*` | 无权限、未找到 | 所有人 |

会话状态为 unknown / authenticated / anonymous / unavailable。unknown 时等待单一共享会话请求；网络故障 unavailable 不应当作已退出并清空草稿。角色 `ban` 不允许提交，后端仍需执行封禁策略。客户端 route meta 和 [导航守卫](https://router.vuejs.org/guide/advanced/navigation-guards.html) 只负责体验，不能替代接口权限。

`returnTo` 仅接受本站以单个 `/` 开始的已知路由，拒绝 `//`、外部 URL 和登录循环。用户退出/切换账号时取消旧用户请求、清空私有 Query 缓存、卸载源码 model；游客访问不复用上一用户资料。

题库 URL 参数：`title`、重复的 `tag`、`page`、`pageSize`（10/20）、`sort`。提交列表参数：`questionId`、`language`、`status`、`page`。参数需解析、校验、规范化；改变筛选归第 1 页，返回页面恢复筛选和滚动位置。首版不提供后端没有数据支撑的“难度”“我的完成状态”筛选，也不按列表当前页伪造全站统计。

## 4. 视觉风格与交互规范

### 4.1 设计语言

整体清晰、克制，以题面与代码为主要内容。浅灰背景、白色内容面板、细边框、少量阴影；题库使用紧凑列表，管理页使用表格。避免大面积渐变、装饰性图表和多层卡片嵌套。品牌首版使用文字标识“NemoOJ”，不依赖待设计 Logo。

| token | 默认值 | 用途 |
|---|---|---|
| `--oj-bg` | `#F6F8FB` | 页面底色 |
| `--oj-surface` | `#FFFFFF` | 面板、表格、表单 |
| `--oj-text` | `#172B4D` | 正文与标题 |
| `--oj-text-muted` | `#5E6C84` | 辅助信息 |
| `--oj-border` | `#DFE5EE` | 分隔与边框 |
| `--oj-primary` | `#2563EB` | 主按钮、链接、选中状态 |
| `--oj-primary-hover` | `#1D4ED8` | 悬停主色 |
| `--oj-success` | `#15803D` | 通过 |
| `--oj-danger` | `#B91C1C` | 错误与危险操作 |
| `--oj-warning` | `#92400E` | 警告、资源超限 |
| `--oj-radius` | `8px` | 输入框、面板 |

间距使用 4/8/12/16/24/32px；页面标题 24px/600，区块标题 18px/600，正文 14–16px，辅助文字不低于 12px。中文字体使用系统无衬线栈，代码使用本地等宽字体栈；不把字体 CDN 作为页面可用前提。普通表格行高约 48px，输入与主要按钮高 36–40px，移动端触控目标至少 44px。

用 token 映射 Element Plus CSS 变量，不逐页覆盖同一颜色。[Element Plus 主题机制](https://element-plus.org/en-US/guide/theming)

### 4.2 布局与响应式

- 普通页面：顶栏 56px，内容最大宽 1200px，桌面左右留白至少 24px。
- 做题页：顶栏 56px，剩余 `100dvh` 内分区滚动；桌面题面 42%、编辑/结果 58%，题面最小 340px、编辑区最小 480px。
- 右侧：工具栏 44px，编辑器占剩余高度，结果抽屉默认 220px，可折叠和拖动；最小编辑高度 260px，矮屏可切换题面/编辑单栏。
- 管理后台：侧栏 208px，顶部标题与动作，表单最大宽约 1100px；窄屏侧栏变抽屉。
- ≥1200px：完整左右工作台；768–1199px：题面/代码/结果标签切换；<768px：优先题面与结果，代码页提供 textarea 降级编辑、复制/下载，提示桌面编辑体验更完整，不强行加载 Monaco。

做题页结构：

```text
┌ NemoOJ / 返回题库              题目标题                   用户 ┐
├ 题面 · 我的提交 ──────────┬ Python 3.12  完整程序  设置  提交 ┤
│ 标题、标签、实际资源限制   │ main.py                          │
│ 题目描述、输入输出约定     │                                  │
│ 公开样例与解释            │            代码编辑器            │
│ 数据范围                 ├ 草稿已保存 ──────────────────────┤
│                          │ 本次提交结果 / 耗时 / 内存         │
└──────────────────────────┴──────────────────────────────────┘
```

### 4.3 状态与可访问性

每页具备 skeleton、empty、error/retry、loaded；有旧数据时刷新失败保留数据并显示行内提示，不整页闪白。判题状态、保存状态用文字与图标配合颜色。结果更新使用 `aria-live=polite`，不抢编辑器焦点。

所有输入有 label，图标按钮有可访问名称；Tab 可遍历，弹窗关闭后恢复焦点；拖拽分隔条同时提供键盘调整/恢复默认按钮。普通文字对比度至少 4.5:1，焦点轮廓清晰；动画 120–180ms，尊重减少动态效果偏好。题面代码块横向滚动，长标题、错误消息和表格不能撑破页面。

## 5. 页面规格

### 5.1 题库

展示题号、标题、标签、提交数、通过率（acceptedNum/submitNum，分母 0 显示 `—`）。标题查询 300ms 防抖，标签首版自由输入/已选 chips，不从当前页推断全站标签全集。分页由服务端 total 驱动。请求竞态由 Query key 和 AbortSignal 处理，迟到响应不能覆盖新筛选结果。

### 5.2 做题工作台

进入流程：加载公开题目详情 → 加载题目语言能力 → 确定语言 → 加载对应版本模板 → 恢复草稿 → 创建 editor model。无可用语言时可继续阅读/导出已有代码，提交禁用并给出原因。

语言选择顺序：该题上次选择且仍支持的语言 → 用户偏好且支持 → 能力接口推荐项。切换语言先保存当前草稿，再恢复目标语言草稿或模板；不能把 Python 代码改个高亮就当作 C++ 代码。异步模板响应必须绑定题目 ID、版本、语言和请求序号，不能覆盖用户已经输入的内容。

题面只展示公开样例；正式评测只显示允许公开的结果与汇总，不返回隐藏输入/期望答案。函数模式显示类名、方法签名、参数与返回值、节点结构和原地修改约定；Runner 与平台 JSON 协议不暴露为用户需要编辑的文件。

“提交”捕获不可变快照 `{questionId, problemVersion, language, code}`，单次请求期间禁用重复点击；用户随后继续改代码不改变这次提交的源码。请求成功获得 ID 后展示可点击的提交链接并自动按 ID 轮询，同时提供“刷新结果”按钮立即查询当前判题状态；用户无需结束监视即可继续产生一条新提交。工作台保留最近一次已完成提交的结果、耗时与内存，刷新后仍能恢复最新未完成提交和最近资源记录。后端用滑动窗口限制提交频率，并另设未完成任务配额。

编译错误和运行错误必须展示沙箱返回的具体诊断。诊断随提交持久化并限制最大长度，包含编译器 stderr、运行时异常或退出码，但不得返回隐藏测试输入、期望答案或系统凭据。

### 5.3 提交记录与详情

我的提交默认按创建时间倒序，显示题目 ID、语言、状态、耗时、内存、提交时间；后端没有题目标题时显示 ID 链接，不能为每行发起 N 次题目请求。详情固定 ID 与题目版本，不以“题目最新提交”替代。

详情包含结果摘要、实际运行环境、资源限额与消耗、脱敏编译/运行诊断、只读源码。缺失指标显示 `—`，0 显示真实 0；内存字段 KB，转 MiB 除以 1024；耗时 ms；时间戳需有时区，前端统一用 Asia/Shanghai 展示并标注。旧数据缺版本或编译日志时显示“未记录”，不得生成虚构日志。

“复制到编辑器”先检查题目版本和语言可用性，目标已有草稿时显示覆盖确认并保留恢复副本。按钮只复制本人/管理员有权查看的源码，不自动提交。

### 5.4 登录与个人中心

注册输入 userAccount、userPassword、checkPassword；当前后端最小长度分别为账号 4、密码 8，前端与后端同步验证，不擅自改变账号大小写或密码空格。注册成功进入登录，不假定注册自动建立会话。

登录失败使用表单内提示，保留账号，密码不写日志/URL/持久化。个人中心首版只编辑昵称、简介；展示已有头像，无上传接口前不展示上传按钮。头像失败回退首字母图标，不传任意 HTML。资料更新后刷新会话查询。密码修改、邮箱验证、找回密码待有对应后端能力后增加。

### 5.5 管理后台

题目表单分“基础信息 / 模式与签名 / 用例 / 资源 / 预览”五区。首版管理台仅 admin，虽然现有 add/edit 接口允许普通登录用户/作者，后端必须同步收紧到确定的管理策略，不能仅隐藏入口。

- 标题当前上限 80，Markdown 内容 8192（按当前 Java 字符长度口径）；标签数组映射请求 `tags`，读取 VO 是 `tagList`。
- 模式 ACM 保存 `problemSpec: null`；FUNCTION 组装规格对象，其内部 `mode` 用当前后端字符串 `function`，与前端归一化的 `FUNCTION` 区分。
- FUNCTION 固定类名 Solution，参数可增删排序、最多 10 项；类型由服务端规格/共享契约提供，首版覆盖 MultiLanguage 白名单。void 不能选 return，原地修改结果按支持的可变类型限制。未知类型禁止发布，不自动退化。
- 当前后端只接受非空参数列表，`parameters:[i,j]` 校验只实现两个索引；UI 先准确表达这一边界，需要零参或更多结果参数时先改后端契约，不自行放宽。
- 用例逐项编辑 input/output/isHidden，新增默认隐藏；明确切换为公开才能作为样例展示。最多 100 条，单输入/输出当前上限 65536 字符。当前后端拒绝空白输入/输出；若要支持合法空输入，应先修正服务端及其测试，不能在前端填空格伪装。
- 资源显示明确单位：时间 ms（当前 1–30000）、内存 MiB（提交前乘 1024 转 KB，当前最大 1048576KB）、输出 KiB（乘 1024 转 bytes；新增一致服务端校验）。不得将前端数值范围当作安全限制。
- 预览复用真实题面组件；签名模板预览由后端对未保存规格生成。测试用例和官方答案表单默认仅在内存，不存浏览器草稿。
- 已发布题目评测字段只读，修改走“创建新版本”。当前没有完整发布/版本 API，按第 9 节补齐；不能把 `update` 调用成功显示为“发布成功”。
- 用户管理列表采用脱敏 DTO；编辑角色必须确认，后端阻止不合理的自封禁/最后管理员降权。首版不暴露当前使用固定初始密码的管理员创建用户功能，不提供硬删除用户入口；注册创建、角色控制即可。

## 6. 组件与组合逻辑设计

### 6.1 组件边界

展示组件只收 props/发 events；页面负责路由，feature composable 负责业务。以下名称为实施约定，可按职责拆分但不能合并成一个巨型 Workspace 组件。

| 组件 | 输入 | 输出/职责 |
|---|---|---|
| AppHeader | user、activeNav、sessionState | login/logout/navigation；无自行请求 |
| AsyncState | loading、error、empty | retry；统一 skeleton/错误/空态插槽 |
| ProblemFilters | modelValue、loading | update:modelValue；防抖由列表 composable 统一处理 |
| ProblemTable | items、pagination、loading | openProblem、pageChange、sortChange |
| ProblemStatement | sanitized content、samples、limits、spec | 题面展示、样例复制；不接收隐藏用例实体 |
| SplitPane | ratio、minSizes、direction | update:ratio；鼠标与键盘调整 |
| LanguageSelector | capabilities、value、disabled | change(languageId)；展示版本和禁用原因 |
| CodeEditor | documentKey、modelValue、language、options、readOnly | update:modelValue、ready、requestSubmit；Monaco 生命周期与 model 管理 |
| EditorToolbar | settings、capability、submitState | settingsChange、resetTemplate、download、submit |
| DraftIndicator | saving/saved/error/conflict、savedAt | retrySave、resolveConflict |
| JudgeStatusBadge | normalizedStatus | 统一文案/图标/颜色，不读原始字符串猜测 |
| SubmissionResultPanel | submission、fetchState | refresh、openDetail；纯文本诊断 |
| SubmissionTable | items、filters、pagination | filter/page/detail；不暴露未授权 code |
| ReadonlySource | source、language、theme | 复制/下载；懒加载编辑器或 pre 展示 |
| ProblemForm | initialValue、editableFields、validation | saveDraft、requestPublish；不直接调用 update |
| SignatureForm | spec、typeOptions、languageSupport | update:spec、validation；稳定本地行 ID |
| TestCaseEditor | cases、constraints | update:cases；公开/隐藏标记与顺序 |
| ResourceLimitForm | limits、constraints | update:limits；单位转换由 adapter 做 |
| ConfirmActionDialog | title、description、busy | confirm/cancel；重置、发布、角色变更等动作 |

CodeEditor 用受控值同步时，父级新值与当前文本相同不调用 setValue，避免清空撤销历史。语言切换优先切 model；用户输入事件与外部恢复操作要能区分，避免 update 回环。展示组件允许 slots，但不用大量布尔 props 承载无关模式。

### 6.2 Composables 与状态归属

| 模块 | 职责与清理 |
|---|---|
| useSession | Query 缓存用户、单次初始化、会话失效事件；不在多个页面重复探测 |
| useProblemList | URL 参数 ↔ 查询 DTO，取消旧查询、分页与搜索 |
| useProblemCapabilities | 校验运行语言/题目版本/模板，失效时禁止新提交 |
| useCodeDraft | IndexedDB 保存、冲突检测、账户隔离、恢复与导出 |
| useEditorModels | 文档 key 对应 model、undo/viewState；离开释放资源 |
| useSubmitCode | 捕获快照、幂等 key、提交请求、超时不确定状态 |
| useSubmissionPolling | 指定 ID 轮询、退避、终局停止、路由离开取消 |
| useProblemForm | dirty、字段校验、请求映射、发布/版本冲突 |

Query key 建议：`['session']`、`['problems', normalizedFilters]`、`['problem', id]`、`['capabilities', id, version]`、`['template', id, version, language]`、`['submissions', userId, filters]`、`['submission', userId, id]`。管理员和普通用户的返回不可共用同一私有 key。切换身份时清理相关缓存，不持久化私有 Query 结果。

## 7. 代码编辑与草稿规范

### 7.1 编辑器配置

首版支持语法高亮、本地智能补全、行号、括号匹配、自动缩进、撤销重做和查找替换。语言映射 java→java、python→python、cpp→cpp。补全目录对应 Java 8、Python 3.12 和 C++17，包含关键字、常用片段、标准库、当前文件符号、常见容器成员，以及核心代码模式的题目函数签名。默认字号 14px（可调 12–22）、行高约 22px、tabSize=4、空格缩进、minimap 关闭、自动换行默认关闭。主题 vs / vs-dark，只影响代码面板。

Ctrl/Cmd+Enter 提交，Ctrl/Cmd+S 保存草稿，Esc 关闭浮层；中文输入法组合输入期间不能触发提交。隐藏在标签后的编辑器重新可见时调用 layout；分隔条变化用 ResizeObserver，卸载时清理 observer、监听、worker 引用和 model。

本地补全采用当前文件的轻量符号扫描与有限类型推断，不承诺跨文件分析、完整类型系统、在线编译诊断或自动格式化。补全不会新增用户代码网络请求；只读 model 不提供可插入建议。Monaco 的语言 model 和 provider 与完整 VS Code 扩展不是同一机制；移动端不下载 Monaco，使用关闭自动纠错与自动大写的 textarea 降级。[Monaco 官方说明](https://github.com/microsoft/monaco-editor)

### 7.2 模板与文件契约

| 语言 | ACM 可编辑文件 | FUNCTION 可编辑文件 | 提示 |
|---|---|---|---|
| Java | Main.java | Solution.java | ACM main；FUNCTION 按签名实现方法 |
| Python | main.py | solution.py | FUNCTION 含 Solution 与 self，节点类型按 SDK 约定 |
| C++ | main.cpp | solution.hpp | FUNCTION 方法为 public，不写 main |

只展示一份用户源代码，系统 Runner/SDK 可用只读说明呈现，不伪装成用户可编辑文件。模板来自后端，包含文件名、languageId、templateVersion、题目版本、源码和安全的使用说明。前端不通过字符串替换自行生成函数签名。

### 7.3 草稿持久化

使用 IndexedDB，key 为 `schemaVersion:userId|guest:questionId:problemVersion:language`。记录 code、templateVersion、updatedAt、revision、editorViewState；偏好单独保存。500ms 防抖写入，提交/切语言/路由离开前主动 flush；不能依赖 beforeunload 中异步保存必定完成。

- 初始化优先同版本草稿，再模板；旧版本草稿提示手动迁移，不能直接套新签名。
- 草稿变化后显示“保存中/已保存/保存失败”，只有事务成功才显示已保存。
- 语言或主题改变不能丢 undo 与光标；上限每个题目保留 3 个语言 model，离开工作台后释放，源码已存盘。
- 多标签页使用 BroadcastChannel 通知 + IndexedDB 事务比较 revision；发现冲突保留双方内容，由用户选择，不静默覆盖。
- 浏览器存储不可用/配额不足时切内存并显著提示可下载；禁止把失败当作保存成功。
- 游客登录后提示迁移当前草稿到该用户，不覆盖已有用户草稿。主动退出前 flush 并询问是否保留本机草稿；默认清除该账号本机代码与恢复副本。因 Session 过期不清草稿，重新登录同账号可恢复；其他账号不能读取前一账号草稿。
- 草稿是本机便利功能，不是安全加密存储；不保存密码、Cookie、测试答案或隐藏用例。临时未保存状态在退出前提供下载，敏感数据不上报遥测。

源码 UTF-8 字节数用 TextEncoder 检查，阈值由能力接口返回；参考现有沙箱单文件 512KiB，但提交 HTTP 层也需同样校验。超限禁止提交并显示当前大小，不能静默截断、trim 源码或把 CRLF 当空格处理。

## 8. 现有 API 对接清单

### 8.1 地址、信封与类型

生产同域访问 `/api`，由网关路由到 user/question；浏览器不请求 judge、sandbox 或 `/inner/**`。现有 gateway 路由为 `/api/user/**`、`/api/question/**`、`/api/judge/**`，本前端只对接前两类公开接口。

```ts
type Id = string;
type LanguageId = 'java' | 'python' | 'cpp';
type ExecutionMode = 'ACM' | 'FUNCTION';
type SubmissionStatus = 0 | 1 | 2 | 3;
interface ApiResponse<T> { code: number; data: T; message: string }
interface PageResult<T> {
  records: T[]; total: number; current: number; size: number;
}
interface JudgeInfoDTO {
  message?: string | null;
  executedTime?: number | null;   // ms
  executedMemory?: number | null; // KB
}
```

分页请求是 `{current, pageSize, sortField?, sortOrder?}`，响应是 `{records,total,current,size}`，不能假定响应字段名 pageSize。业务成功是 `code === 0`，当前异常处理通常返回业务信封，HTTP 200 不代表操作成功。

实体 ID 使用 Java Long；当前未检索到统一 Long→字符串的响应配置。**前端把 JSON number 转成 string 不能挽回已丢失精度**。首版联调前需由后端将 ID 字段及创建/提交返回的顶层 ID 明确序列化为十进制字符串；请求使用字符串并做契约测试。只调整标识符，不把耗时、分页数等全部 Long 随意改成字符串。用 `9007199254740993` 等超出 JS 安全整数范围的 ID 做往返测试。

后端旧时间字段若无时区，不在前端猜测 UTC；统一输出带 offset 的 ISO 8601，旧数据转换由后端/适配层按明确部署时区处理。有限资源值可用 number，FUNCTION 样例中的 64 位整数不能经普通 JSON.parse→stringify 改写，原文保留，必要时用无损 JSON 校验器或交服务端校验。

### 8.2 用户接口（现有路径）

| 方法与路径 | 请求 | 返回 data | 使用规则 |
|---|---|---|---|
| POST `/api/user/register` | userAccount,userPassword,checkPassword | ID | 空参数当前可能返回 null，客户端应判协议异常；后端改为统一错误信封 |
| POST `/api/user/login` | userAccount,userPassword | LoginUserVO | 成功后刷新会话/CSRF 状态 |
| GET `/api/user/get/login` | 无 | LoginUserVO | 未登录 40100，不是账号不存在 |
| POST `/api/user/logout` | 无 | boolean | 成功后清理私有缓存与本机代码策略 |
| POST `/api/user/update/my` | userName,userProfile；userAvatar 可选 | boolean | 首版 UI 只编辑昵称/简介 |
| POST `/api/user/list/page` | 分页、userName、userRole 等 | 当前 Page<User> | admin；后端换脱敏 DTO 后才接入，禁止密码散列进入浏览器 |
| POST `/api/user/update` | id,userRole=`user\|ban` | boolean | admin 只能封禁或解除封禁普通用户；禁止创建、删除、提升管理员或修改管理员账号 |

LoginUserVO 字段：id、userName、userAvatar、userProfile、userRole（user/admin/ban）、createTime、updateTime。个人页不依赖不存在的邮箱、积分或解题统计。不要使用 `/user/get` 的实体响应作为普通用户资料。

### 8.3 题目与提交（现有路径）

| 方法与路径 | 请求 | 返回 data | 当前限制/用途 |
|---|---|---|---|
| POST `/api/question/list/page/vo` | current,pageSize,title?,tags?,sortField?,sortOrder? | Page<QuestionVO> | pageSize ≤20；无 difficulty/完成状态过滤 |
| GET `/api/question/get/vo?id=...` | id | QuestionVO | 不含结构化样例、judgeConfig，需扩展 |
| POST `/api/question/question_submit/do` | questionId,language,code | ID | 登录；异步判题；需扩展版本与幂等契约 |
| POST `/api/question/question_submit/list/page` | 分页、questionId?,language?,status?,userId? | Page<QuestionSubmitVO> | 登录；当前查询忽略 id 字段，不能替代按 ID 详情接口 |
| POST `/api/question/list/page` | 分页、id?,title?,tags? | Page<Question> | admin；含评测数据，只用于管理台 |
| POST `/api/question/add` | title,content,tags,answer?,judgeCase,judgeConfig,problemSpec | ID | 当前只要求登录，首版管理策略需收紧 |
| POST `/api/question/update` | id 加待更新字段 | boolean | admin；已发布评测字段受保护 |
| POST `/api/question/delete` | id | boolean | 当前作者/admin；首版只允许删除未发布草稿，并需后端验证 |

公开 QuestionVO：id、title、content、tagList、submitNum、acceptedNum、thumbNum、favourNum、userId、user、problemSpec、problemVersion、isPublished、createTime、updateTime。thumbNum/favourNum 虽存在，首版无对应操作接口，不做可点击点赞收藏。当前管理实体的 tags 是 JSON 字符串，与 VO 的 tagList 不同，转换集中处理。

QuestionSubmitVO：id、language、code（可能 null）、judgeInfo、status、questionId、userId、createTime、updateTime。当前仅本人/admin 可见 code，其他人 null；首版详情进一步限定本人/admin。当前列表传 userId 不是权限边界，“我的提交”后端必须绑定 Session 用户。

排序 UI 内部只用 asc/desc，adapter 映射 ascend/descend；源码 CommonConstant 的降序值有前导空格，后端应统一 trim/白名单，勿将空格作为新契约。合法 sortField 先限制 createTime/title 等已确认列，不接受任意 URL 值透传数据库。

## 9. 必须补齐的后端契约与依赖

本节均为**设计中的新增或调整**。先以 DTO、服务端实现、契约测试确认，再接入前端；接口命名若因现有规范调整，必须同步本文与客户端。

### B01：公开题目数据与发布边界（P0）

扩展 `/question/get/vo`：新增 `judgeConfig`（仅限额）、`samples: {input,output,explanation?}[]`。samples 只由明确 `isHidden=false` 的用例生成，null 视为隐藏；没有解释存储时 explanation 缺省。公开列表和详情必须在服务端排除未发布题目，提交接口也检查发布状态，管理员预览使用独立管理接口。

原公开查询未见 isPublished 过滤，不能靠前端过滤当前分页补救，否则会泄漏草稿且 total 错误。上线前审计旧题目发布标记，不能自动把全部存量题目设为公开。

### B02：题目语言能力与模板（P0，依赖 MultiLanguage）

新增 GET `/api/question/languages?questionId=<id>`，返回：

```ts
interface ProblemCapabilities {
  questionId: Id;
  problemVersion: number;
  mode: ExecutionMode;
  capabilitiesVersion: string;
  expiresAt: string;
  recommendedLanguage: LanguageId | null;
  languages: Array<{
    id: LanguageId;
    displayName: string;
    runtimeId: string;
    enabled: boolean;
    disabledReason?: string;
    maxCodeBytes: number;
    effectiveLimits: {
      timeLimitMs: number; memoryLimitKB: number; outputLimitBytes: number;
    };
  }>;
}
```

新增 GET `/api/question/code_template?questionId=<id>&language=<id>&problemVersion=<n>`，返回 `{questionId,problemVersion,language,mode,fileName,templateVersion,code,instructions}`。instructions 为受控文本/净化 Markdown。只对已发布且支持的组合返回，不返回 Runner、镜像地址、源码宿主路径或密钥。

能力是实际可用集合，前端 Query 可缓存至 expiresAt，过期刷新失败禁用新提交并保留编辑；后端每次提交重新校验。Java-only 环境只展示已启用 Java；支持中的 cpp/python 可显示禁用项与明确原因，不能提供可提交的假模板。

### B03：提交详情与安全诊断（P0）

新增 GET `/api/question/question_submit/get/vo?id=<id>`，本人/admin 可读，不存在 40400、无权限 40101。返回现有提交 VO 加：

```ts
interface SubmissionDetailsExtra {
  problemVersion: number | null;
  verdict: string | null; // 稳定机器值，沿用 accepted/compile_error 等
  runtime: { runtimeId: string; displayName: string } | null;
  effectiveLimits: {
    timeLimitMs: number; memoryLimitKB: number; outputLimitBytes: number;
  } | null;
  diagnostics: {
    phase: 'compile' | 'run' | 'system';
    message: string;
    truncated: boolean;
  } | null;
}
```

上限与安全：诊断建议最多 16KiB UTF-8；编译日志映射为用户虚拟文件名，去除宿主机路径和平台细节。隐藏测试产生的 stderr/程序输出可能包含测试输入，首版不原样返回，只给错误类型；只有公开样例或明确安全的日志才显示。平台故障展示通用提示和追踪 ID，不给内部堆栈。旧记录字段可为 null，前端按“未记录”处理。

当前判题持久化只有 message/time/memory，要展示详细日志必须同步保存允许公开的诊断，不能在前端推测。详情轮询与列表采用同一终局状态语义，不能由编译输出是否为空判定成功。

新增 POST `/api/question/question_submit/my/list/page`，请求只允许分页、questionId/language/status；由 Session 强制绑定用户 ID。原全局提交列表不用于“我的提交”权限隔离。

### B04：版本校验、幂等提交与响应丢失恢复（P0）

扩展正式提交请求：`{questionId,language,code,problemVersion,clientRequestId}`；仍返回提交 ID。clientRequestId 为每次明确点击生成的 UUID，保留该次源码快照。服务端按 `(userId,clientRequestId)` 建唯一约束，比较快照摘要：同键同请求返回同 ID，同键不同请求拒绝；提交记录与消息投递遵循已有可靠投递方案，不因幂等重复生成任务。

题目版本变化时拒绝新提交，响应可辨识错误标识 `PROBLEM_VERSION_CHANGED`；客户端保存草稿、提示加载新版本，不能偷偷替换函数签名后重发。保留现有 BaseResponse，新增可选 `errorKey`（错误时使用），例如上述版本错误、`CAPABILITY_UNAVAILABLE`、`REQUEST_ID_CONFLICT`、`CSRF_INVALID`；禁止依赖中文 message 文本匹配。

新增 GET `/api/question/question_submit/by_request?clientRequestId=<uuid>`（仅本人），找到返回 ID/状态，尚未找到可返回 data=null。null 只表示暂未查到，不能证明原请求未被处理；同 key 同快照的重试只有在幂等契约就绪后才安全。

前端默认不自动重试 mutation。网络超时显示“提交结果尚未确认”，先按 requestId 查询；允许用户使用相同 key 与冻结快照重试，代码变更后的新提交用新 key。幂等未实现时不得以重新 POST 冒充恢复，提示查历史并保留草稿。

### B05：管理台发布与版本（P0）

- 新增 GET `/api/question/admin/detail?id=<id>`：admin 专用完整表单 DTO，返回 rowVersion（并发编辑版本）、problemVersion、isPublished、tags 数组、题面、答案、judgeCase、judgeConfig、problemSpec。
- 扩展 `/question/update` 请求携带 expectedRowVersion，后端 CAS 更新，冲突给 `EDIT_CONFLICT`；旧页面不能覆盖他人的修改。
- 新增 POST `/api/question/admin/preview`：提交未保存表单规格，返回各语言模板及字段校验结果，不执行用户代码、不持久化。
- 新增 POST `/api/question/admin/publish`：`{id,expectedRowVersion}`；后端完整校验用例、签名、运行能力后原子发布。重复发布同版本可安全返回既有状态。
- 新增 POST `/api/question/admin/new_version`：`{id,expectedRowVersion}`；从已发布题目创建新 ID 的未发布副本，problemVersion 增加并持久化版本关联。原题 ID/评测数据与历史提交保持可追溯；草稿编辑不影响原版本。
- 发布新版本时以显式的版本关联切换题库当前展示项，旧题目链接展示“历史版本”并保留提交查询；禁止在浏览器把内容相似的题目猜成同一版本。完整版本关联需数据库迁移，复用 deploy/migration 的方式。
- 发布后首版评测表单只读；删除只针对未发布、无提交引用草稿。普通账号不得调用 add/edit/delete 来绕过管理台策略。

模板预览不等于真实判题验收。发布流程需保存状态明确，未满足后端契约时按钮禁用且说明原因，不显示假的成功提示。

### B06：账号、安全和通用契约（P0）

将管理员用户列表/详情改为脱敏 DTO（id、userAccount、userName、userRole、头像/简介、时间等），不返回 password hash、第三方标识和不必要的实体字段。全网关阻断 `/inner/**` 对外访问；管理员权限与 ban 状态服务端校验。

Session 延续现有 Redis，不迁 JWT。CSRF 采用共享 Session 的同步 token 方案：新增 GET `/api/user/csrf` 返回 `{token,headerName:'X-CSRF-Token'}`；token 仅前端内存，登录/退出/会话更新后刷新。user/question 的写操作校验 token，包括登录与注册；分页 POST 等只读路由用明确白名单管理，不能“一律 POST 免校验”。缺失/错误 token 返回 403 与 `errorKey=CSRF_INVALID`；自动刷新 token 后不自动重放提交/发布等写请求。实现须与原 T04 合并，若 T04 已实现等效契约则复用并同步此处名称，不再建第二套。

前端不会读取 HttpOnly Session Cookie，Cookie 由浏览器携带。跨域必须精确来源、允许凭据；优先同域，CSRF 不由 CORS 替代。ID 精度、时间格式、source 字节限额、脱敏和分页上限列入同一批 API 契约测试。

## 10. 请求封装、轮询与状态机

### 10.1 统一客户端

`request<T>()` 负责：固定 base `/api`、`credentials:'include'`、JSON Content-Type、AbortSignal、默认 15s 请求期限、CSRF header、HTTP 与业务错误解析、信封 schema 检查。普通查询超时只说明请求失败，不代表判题超时；正式提交 API 应快速入队，不等待完整编译执行。

错误分类：network / timeout / aborted / http / business / protocol。aborted 不弹错误通知；非 JSON 502/HTML 响应给可读服务异常，不把解析堆栈显示给用户。

| 条件 | 前端行为 |
|---|---|
| HTTP 401 或业务 40100 | 单次会话失效事件，保存当前工作，提示登录；禁止多个并发请求反复跳转 |
| 40101、40300 或 HTTP 403 | 权限/禁止访问提示；CSRF_INVALID 单独处理，不当成退出 |
| 40400/HTTP 404 | 页面不存在或提交不可用；停止轮询 |
| 40000 | 表单/操作行内错误，保留输入 |
| 50000/50001/50002、HTTP 5xx | 服务失败提示，查询允许有限重试；写操作不自动重发 |
| HTTP 429 | 按 Retry-After 冷却；无值默认 5s，不循环请求 |
| 返回 null/缺信封/未知 code | 协议错误，不展示成功 |

Query retry 显式设定：只读请求网络/5xx 最多重试 2 次，权限/参数/不存在不重试；mutation retry=false。TanStack Query 自身有查询重试机制，必须避免与 fetch 内层重复退避。[官方重试说明](https://tanstack.com/query/latest/docs/framework/vue/guides/query-retries)

### 10.2 提交流程

```text
可编辑 → 本地校验 → 提交请求中
  → 获得 submissionId → 等待(0) → 判题中(1) → 通过(2) / 未通过(3)
  → 请求结果不确定 → 按 clientRequestId 恢复 → 同一 submissionId
  → 明确拒绝 → 保留代码并显示原因
```

状态 0/1 为未终结，后端重试可能 1→0，前端允许回退到等待；2/3 为终局。判题中的 UI 不伪造百分比、队列位置或预计秒数，当前没有该数据。

新提交立即查询一次，前 30s 每 1s，30–120s 每 2s，120–300s 每 5s，之后每 10s 继续查询直至终局；无需用户手动结束监视。单 ID 同时最多一个请求，终局自动停止；新提交会把工作台切换到新 ID，旧提交仍可从记录页查看。刷新页面从保存的非敏感 submissionId 恢复，不重发源码。

### 10.3 状态映射

主状态先看 status，终局再看 verdict（新字段）或现有 judgeInfo.message：

| 原始值 | 展示 |
|---|---|
| status=0 / 1 | 等待判题 / 判题中 |
| status=2 且结果一致 | 通过（AC） |
| compile_error | 编译错误（CE） |
| runtime_error | 运行错误（RE） |
| wrong_answer | 答案错误（WA） |
| time_limit_exceeded | 时间超限（TLE） |
| memory_limit_exceeded | 内存超限（MLE） |
| output_limit_exceeded | 输出超限（OLE） |
| presentation_error | 格式错误 |
| dangerous_operation | 不允许的操作 |
| language_not_supported | 当前语言不可用 |
| system_error | 判题服务异常 |
| unknown/null/不一致终局 | 结果异常或未通过；不能默认 AC |

现有 message 主要为小写下划线枚举，并非注释中的 `Accepted`；MQ 失败还有 `System Error: MQ unavailable` 遗留值。后端统一 machine verdict，前端 adapter 仅维护已知旧值兼容表，未知值给通用提示与脱敏文字，不把错误文本当 HTML。

## 11. 部署、隐私与性能

### 11.1 本地与生产

本地 Vite 默认 5173，将 `/api` 代理到 `http://localhost:8101`，保留完整路径。当前 CORS 默认 8080，若选择浏览器直连网关需同步配置；首选代理。dev 仅开发环境使用非 Secure Cookie，不能为让 HTTP 测试通过而削弱生产 Cookie。

生产静态资源和 `/api` 使用同域 HTTPS；网关只反代 `/api`，SPA history fallback 仅页面路径。带内容 hash 的 assets 长缓存，index.html 不长期缓存；旧版本资源至少保留一个部署切换窗口。部署不将源码、用户代码、沙箱密钥或数据库配置放入前端构建。

环境文件建议仅 `VITE_API_BASE_URL=/api` 与非敏感构建标识。VITE_ 变量会暴露给客户端，绝不能存 SANDBOX_SECRET 等服务端秘密。[Vite 环境变量文档](https://vite.dev/guide/env-and-mode)

### 11.2 内容与数据边界

Markdown 渲染禁用原始 HTML，再用 DOMPurify 净化；链接限制安全协议，外链 `noopener noreferrer`，图片加载失败有占位。净化后不再插入不可信 HTML；代码/诊断只用文本节点。添加恶意链接、事件属性、SVG 等回归用例。[DOMPurify 官方说明](https://github.com/cure53/DOMPurify)

隐藏用例、官方答案、别人的源码必须从响应中排除，不能靠 CSS/折叠隐藏。普通页面不持久化完整 API 缓存；管理用例和答案只在有权限页面内存中存在。日志/错误上报不记录源码、密码、Cookie、测试用例正文；截图与 E2E traces 使用测试账号及合成数据。

生产 CSP 与 Monaco worker、静态资源加载一并验证，不为消除控制台报错直接开放通配源。Session 失效、管理员降权后立即清理管理表单与私有缓存，同时保留本人编辑草稿的恢复机制。

### 11.3 性能与稳定性目标

题库路由不下载 Monaco；管理页、编辑器与 Markdown 代码高亮按需加载。题目列表每页最多 20；不为整张表每行加载编辑器。限制并释放 model、轮询与事件监听，重复打开/离开做题页不能持续积累后台请求。

建议初始预算：题库首路由 JS ≤300KiB gzip（不含异步编辑器），CI 记录 bundle 分析；超过预算应解释并优化。以 1440×900、普通桌面 Chrome、固定模拟网络环境记录首屏与交互测量，目标首屏主体 2.5s 内可见、输入无明显卡顿。真实后端延迟单独记录，不用 mock 结果证明上线性能。

## 12. Agent 实施任务与完成标准

### 12.1 分阶段任务表

| ID | 任务 | 依赖 | 可验收产物 |
|---|---|---|---|
| F00 | 核对代码与 API 契约 | 本文、MultiLanguage | 现有/缺口清单、ID/状态/权限契约 fixtures；明确 B01–B06 负责人和状态 |
| F01 | 创建工程、路由、token、布局 | F00 | npm ci/build/type-check/lint 可运行；主路由和错误页；锁文件 |
| F02 | 请求层、登录、CSRF、权限、个人中心 | F01、B06 | Session 恢复、过期/网络区分、注销隔离、资料更新 |
| F03 | 题库与题面 | F01、B01 | 搜索/标签/分页/URL 恢复、样例、资源限制、净化 Markdown |
| F04 | Monaco、模板、草稿 | F02、F03、B02 | 三语言能力匹配、主题、切换/刷新不丢代码、冲突与存储失败处理 |
| F05 | 提交、轮询、记录和详情 | F04、B03、B04 | 按 ID 闭环；重复点击/超时恢复；结果映射、源码权限 |
| F06 | 题目与用户管理 | F02、B05、B06 | 结构化签名/用例/资源、预览发布新版本、用户角色控制 |
| F07 | 真实联调与部署验证 | F03–F06、后端多语言里程碑 | 三语言×两模式端到端、浏览器/视口验证、真实 Session/网关及回滚 |

F01–F06 可用显式 mock 提前开发展示和错误状态，但每页注明开发数据状态，生产不包含自动 mock 回退。若只分配前端任务且后端契约未就绪，agent 继续完成无依赖部分并列出阻塞项；不得把按钮接到假接口、数据库直连或 `/inner` 接口凑齐功能。

本文不要求多 agent 并行。实施 agent 按分配范围执行；需要修改后端时遵循原模块规则，代码更改写 `record/log/`。本次设计文档本身不代表已经授权发布线上站点。

### 12.2 必须执行的验证

| 编号 | 验收场景 | 通过标准 |
|---|---|---|
| A01 | 游客/登录用户/admin/ban 访问 | 页面与服务端一致，无越权接口数据；unknown 不闪管理页 |
| A02 | 超长 ID | ID 从响应→路由→请求全程无损，含创建返回 data ID |
| A03 | 草稿 | 切语言、切题、刷新、过期重登、双标签冲突、配额失败均不静默丢代码 |
| A04 | 模板竞态 | 快速切题/语言、模板慢响应不覆盖新输入；题目版本改变有提示 |
| A05 | 提交幂等 | 双击/快捷键并发/响应丢失/刷新恢复不创建重复任务；不自动重放 POST |
| A06 | 状态机 | 0→1→2、0→1→3、1→0、未知状态、长等待、离线恢复均正确；终局不再轮询 |
| A07 | 三语言两模式 | Java/Python/C++ 的 ACM/FUNCTION 模板与真实后端执行一致，不只检查 UI 高亮 |
| A08 | 资源与日志 | ms/KB/MiB 正确；null 不显示成 0；CE/RE/TLE/MLE/OLE 各有可读状态 |
| A09 | 数据保密与 XSS | 普通响应无隐藏数据、跨账号无源码缓存；恶意 Markdown/日志无法执行脚本 |
| A10 | 管理工作流 | 草稿→预览→发布；已发布只读→新版本；并发编辑冲突；角色降权清缓存 |
| A11 | 请求故障 | HTTP 200 业务错误、非 JSON 502、40100、403、429、超时不出现假成功或重试风暴 |
| A12 | 浏览器与布局 | 1440×900、1024×768、390×844；桌面 Chrome/Edge/Firefox/Safari 实测或明确未验证；移动阅读/textarea 降级 |
| A13 | 编辑器可访问性 | 键盘可提交/保存/调面板；中文输入法正常；主题对比、焦点、放大至 200% 不遮挡核心操作 |
| A14 | 部署 | HTTPS Cookie、深层路由刷新、API 不落 SPA、worker 本地加载、无公网 CDN 依赖、旧资源切换 |

单元测试优先覆盖 DTO adapter、状态映射、资源单位/字节数、URL 参数、草稿冲突与轮询停止；组件测试覆盖确认与编辑器外部值同步。Playwright 覆盖真实用户路径，至少一组连接真实测试后端和 Linux Docker 判题，mock 套件另行标明，不能混淆证据。

工程至少提供并通过：`npm run type-check`、`npm run lint`、`npm run test:unit`、`npm run build`、`npm run test:e2e`。禁止用 `as any` 大面积消除接口错误或关闭 strict 来通过构建。

### 12.3 最终交付清单

1. 前端源码、锁文件、.env.example、启动/代理/构建/部署 README。
2. API 契约与 B01–B06 完成状态；实际有变化的字段同步文档。
3. 关键页面截图：题库、两种模式工作台、AC/CE/服务错误、管理表单、移动阅读。
4. 单元/组件/E2E 与真实多语言联调结果，说明命令、环境、通过/失败与未验证项。
5. 无未说明的 mock/TODO/假成功按钮；没有后端支持的功能不宣称完成。
6. 每次代码改动的记录；明确已知限制与上线剩余项。原有 Java 能力与多语言方案回归通过。

## 13. 选择理由与后续决策

| 选择 | 对比项 | 本次理由 |
|---|---|---|
| Vue SPA | SSR/独立 Node 中间层 | 主要业务是登录后的编辑与判题，现有网关即可承接 API；降低部署复杂度 |
| 学生端与管理端同工程 | 两个独立前端 | 当前体量便于复用题面、模板和基础样式；按路由拆包、后端权限隔离 |
| Monaco 桌面 + 移动降级 | 首版建设完整 Web IDE | 聚焦代码编辑与判题闭环，语言服务和调试可后续独立扩展 |
| 有界轮询 | 首版 WebSocket/SSE | 当前为异步提交模型且无推送接口，轮询便于失败恢复和按 ID 查询 |
| 服务端生成模板与能力 | 前端硬编码三语言模板 | 与 ProblemSpec、运行时和题目版本一致，避免多语言上线时两边漂移 |

具体 npm 版本、部署域名、CI 平台和目标浏览器版本在实施启动时按实际环境锁定；不影响本次已确定的产品设计。独立试运行、移动完整编辑体验或全站深色主题属于新增范围，另行设计契约后实施。
