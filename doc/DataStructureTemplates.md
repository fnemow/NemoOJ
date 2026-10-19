# 常用数据结构模板

本批在代码编辑区新增“数据结构”选择框和“插入模板”按钮，支持 Java 8、Python 3.12、C++17。选择后在合适的文件级位置插入完整辅助类，保留原 Solution 和草稿。桌面端可以 Ctrl+Z 撤销；移动端继续使用 textarea。只读编辑器不显示插入入口，已有同名类不会重复插入。

## 模板列表

| 类名 | 内容 | 用户需要实现或维护的部分 |
| --- | --- | --- |
| RBNode | val、red、left、right、parent | 旋转、颜色更新、父指针及平衡维护 |
| NaryNode | val、children | 遍历、增删节点 |
| GraphNode | val、neighbors | 遍历、克隆、visited 等 |
| RandomListNode | val、next、random | 身份关系与复制算法 |
| DoublyListNode | val、prev、next | 增删时双侧链接 |
| TrieNode | children[26]、end | 小写英文字母的插入、查询、删除 |
| UnionFind | find、unite、connected、count | 具体题目的合并条件 |
| FenwickTree | add、prefixSum、rangeSum | 具体题目的更新与查询 |

现有 ListNode 和 TreeNode 在核心代码模式由平台提供，本批不重复定义，以免与 Runner 冲突。新增辅助类属于用户代码，按需插入，不会在每份提交自动添加八类代码。

## 使用约定

- RBNode 的 red=true 表示红色，空指针/None 表示黑色 NIL。创建一个节点不会自动建立父子关系。
- GraphNode 允许环；遍历时自行去重，不能用树的递归序列化直接处理图。
- Python children/neighbors 均为实例独立列表，避免可变默认参数导致节点共享列表。
- C++ 节点指针不自动递归释放，避免图环、random、parent 导致重复释放；解法需要管理自行分配的节点生命周期。
- UnionFind 编号为 0..n-1，unite 返回是否发生实际合并，count 返回剩余集合数。
- FenwickTree 接口为零起始下标；prefixSum(end) 求 [0,end)，rangeSum(left,right) 求 [left,right)，允许空区间。内部使用一基下标。Java 用 long，C++ 用 long long；应用仍需保证数值不超过 64 位范围。
- 并查集与树状数组对非法大小、下标和反向区间明确报错，避免树状数组零步长死循环。
- Java 模板使用完整集合类型名；C++ 插入必要标准头文件，ACM 模式同样可编译。Python 保留模块文档、编码声明和 future import 的位置。

## 与判题输入类型的区别

“有辅助类”不代表“可以直接用该类作为题目参数”。本批没有新增 DSL 类型、JSON/ACM 节点编解码或结果比较规则。题目的签名仍只使用已有受支持类型；红黑树等结构可通过 root、keys、colors、left、right 数组输入，再由用户解法按需构建节点。

后续若让 RBNode、GraphNode 等直接成为入参，需要同步扩展三个 Runner、两种输入协议、AI schema、前端签名和发布验证。图引用、父指针、颜色、重复键、多种合法树形等必须有明确约定，不能只加类名就放行。

## 本地验收（2026-10-14）

- 24 份模板（8 类×3 语言）实际编译/运行：Java 使用 --release 8，C++ 使用 -std=c++17，Python 使用现有生成验收环境。
- 每语言 906 个断言，覆盖随机并查集合并/连通性与集合数、树状数组负数更新和区间和、空结构、非法下标、节点默认字段和列表独立性。
- 19 个相关前端测试通过；其中只读组件测试首次因未等待 Vue 异步切换到移动编辑器失败，补充 nextTick 后通过，没有修改产品逻辑或放宽断言。
- TypeScript 类型检查、修改文件 ESLint、Vite 生产构建通过；构建保留已有大块体积警告。
- 真实浏览器本地组件预览：桌面插入/撤销保留原代码，390px 手机宽度下插入与 textarea 显示正常。临时预览文件、服务和浏览器窗口验收后清理。
- 后续已部署网站，包含全部模板的三语言普通提交均 AC；红黑树三档私人题通过完整验证。详见 `record/log/2026-10-14-redblack-site-deployment.md`。线上浏览器观察因自动权限审核超时未完成，不将此前本地浏览器检查冒充线上按钮验收。

复验命令（项目根目录，PowerShell）：

```powershell
& .\build\generation-venv\Scripts\python.exe .\deploy\ops\verify_data_structure_templates.py
```
