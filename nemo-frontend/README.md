# NemoOJ Frontend

NemoOJ 的 Vue 3 前端，覆盖题库、三语言做题工作台、提交记录、个人中心和管理后台。接口统一经同域 `/api` 访问，开发环境由 Vite 代理到 `http://localhost:8101`。

## 环境与启动

- Node.js `>=20.19.0`（已用 Node 22 验证）
- npm；CI 和可复现安装使用 `npm ci`
- 网关及 user/question/judge/sandbox 服务按仓库部署文档启动
- 数据库执行至 `deploy/migration/V7__frontend_contract.sql`

```bash
cp .env.example .env.local
npm ci
npm run dev
```

浏览器访问 `http://localhost:5173`。环境变量只允许放公开构建信息；`VITE_` 变量会进入浏览器产物，禁止写数据库密码、Session、沙箱密钥或内部服务凭据。

## 验证命令

```bash
npm run type-check
npm run lint
npm run test:unit
npm run build
npm run test:e2e
```

`test:e2e` 首次运行前执行 `node ./node_modules/playwright/cli.js install chromium`。当前浏览器用例用明确的网络 fixture 验证游客题库和做题页，不代表真实后端联调；真实多语言 Docker 判题证据见仓库 `record/log`。

## 部署

`npm run build` 产出 `dist/`。生产服务应满足：

- 页面路径回退到 `index.html`，`/api/**` 必须先反代到网关，不能被 SPA fallback 接管；
- `index.html` 使用短缓存，带内容 hash 的 `assets/` 使用长缓存；
- 页面与 API 同域 HTTPS，Session Cookie 保持 HttpOnly、Secure、SameSite；
- 允许 Monaco 的本地 worker 静态资源，不使用公网 CDN；
- 发布前应用 V7 迁移，并启用生产 CSRF 同步 token 校验。

## 数据与安全边界

- 源码草稿保存在 IndexedDB，按账号、题目版本和语言隔离；测试用例、密码和 Cookie 不写本地草稿。
- Markdown 禁用原始 HTML，并经过 DOMPurify；诊断与源码按纯文本展示。
- 管理表单的隐藏用例和参考答案只驻留当前页面内存。
- 所有 ID 都按十进制字符串处理，避免 Java Long 在浏览器中丢失精度。

接口完成状态与联调边界见 [docs/api-contract.md](docs/api-contract.md)。
