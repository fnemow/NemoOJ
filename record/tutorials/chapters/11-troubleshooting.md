<a id="troubleshooting"></a>

## 第十一章：报错时怎样排查

### 11.1 固定排查顺序

先确认终端与当前目录；再确认 Python 路径；再查依赖；然后运行最小离线脚本；最后才排查真实 API。框架、供应商、业务上下文、权限、预算和网络是不同问题，不要把所有异常都写成“模型不好”。

PowerShell 下：

```powershell
Get-Location
labpy -c "import sys; print(sys.executable); print(sys.version)"
labpy -m pip check
labpy lab01_chat.py
```

在输出正常后再加 `--online`。如果离线分支都出错，先不要消耗真实模型费用。

### 11.2 环境与文件问题

| 症状 | 常见原因 | 处理 |
| --- | --- | --- |
| `Get-Content` 不是内部命令 | 在 CMD 用了 PowerShell 语法 | 换 PowerShell；或按 CMD 等价命令操作 |
| 找不到 `py` | Python Launcher 未安装/不在 PATH | 检查可用 Python；重新打开终端 |
| 找不到 `labpy` | 新终端没定义函数 | 回到 labs，重新定义 0.3/0.4 的函数 |
| 激活脚本被禁止 | PowerShell 执行策略 | 本教程直接用 venv Python，不必修改策略 |
| `ModuleNotFoundError: langchain` | 用了系统 Python | 输出 sys.executable，使用教学 venv 安装依赖 |
| 向量检索提示找不到 NumPy | 未安装完整锁定依赖 | 用当前 venv 安装 requirements-lock.txt |
| pip 安装成功但执行失败 | pip 与 Python 不同环境 | 总是 `目标Python -m pip ...` |
| 找不到 data 文件 | 把单个脚本拷走了 | 保留 common/rag_utils/data 相对结构 |
| 练习 11 找不到 nemo_agent | 不在完整 NemoOJ 树中 | 保留项目与 tutorial 目录关系 |
| 中文乱码 | 控制台/文件编码不一致 | 用 `-X utf8`，文件保存为 UTF-8 |
| 目录已有 .venv 但不能运行 | 从另一台机器复制虚拟环境 | 重新创建 venv，重新安装依赖 |

安装依赖如果需要代理/镜像，使用你已有的可信设置。不要把一个失败命令的网络问题通过随意删掉依赖解决；修复后重新运行测试。

### 11.3 LangChain 与 LangGraph 问题

| 症状 | 原因与处理 |
| --- | --- |
| 模板说缺少变量 | `.invoke({...})` 未提供模板需要的全部键；检查变量名 |
| JSON 花括号被当变量 | 模板中固定 JSON 的花括号需按模板规则转义，或改用独立变量传入 |
| AI 消息正文为空 | 先看 `tool_calls`，工具请求可能没有正文 |
| 脚本返回 coroutine | 忘了 await；普通脚本用 asyncio.run，已有事件循环里直接 await |
| GraphRecursionError | 循环不结束或图步数超限；先检查路由/上限，不只调大 recursion_limit |
| InvalidUpdateError | 并行写同一字段缺少正确 reducer，或节点返回值不符合状态更新约定 |
| trace 重复越来越多 | reducer 已追加，节点却再次返回全部历史 |
| 恢复时保存动作重复 | interrupt 前做了副作用，或外部写入没有幂等键 |
| `Task exists` | 持久化任务已建立；用 status/resume 或换新 task ID |
| `Task does not exist` | 没 start、task ID 不同、输出目录变了、读的是另一份 SQLite |
| `Task already completed` | 图已经结束；无需再次 resume |
| SQLite 中断丢了 | 每次用了不同目录/内存库，或没提供 checkpointer |
| Mock 对问题不“聪明” | 它只返回固定响应；使用它验证框架，真实决策另测 |

### 11.4 真实 API 问题

| 症状 | 检查方向 |
| --- | --- |
| 配置提示 model 未填写 | 使用当前控制台允许的 API model ID，不能保留占位符 |
| 401/403 | Key、账号权限、服务地址、模型资格；不要把完整 Key 打印到日志 |
| 404/model not found | 模型 ID 或 base URL 不正确，检查供应商当前文档 |
| 429 | 额度、RPM/TPM、并发；降低并发，有限退避，不无限重试 |
| 400 tools/schema 错误 | 供应商对工具与 JSON Schema 的支持不同；先测单个简单工具 |
| 01 成功，03 失败 | 聊天兼容不代表结构化调用兼容；检查 function_calling 路径 |
| thinking/extra_body 被拒绝 | 扩展参数是供应商专属；按当前文档确认，不向不支持的服务发送 |
| JSON 被截断 | 输出预算不足或结果过大；收缩 Schema/内容，检查 finish reason |
| 没 usage | 供应商/适配路径未返回；生产账本记 UNKNOWN，不能假设免费 |
| 超时 | 网络、模型排队、输入/输出规模；保留调用记录，避免盲目重复付费 |
| 模型调用没结束 | 检查工具循环与次数；读最后工具结果，是否一直重复查询 |

真实供应商输出不可保证与离线样例逐字相同。按结构、来源、行为和预算验收，而不是固定答案字符串。

### 11.5 RAG 与训练问题

| 症状 | 检查方向 |
| --- | --- |
| 查到不相关片段 | 先查看切块与召回内容，再改查询/检索器，不能只改回答提示词 |
| 返回近邻但没有依据 | 相似度不是正确率，增加无依据分支和标注校准 |
| 新 FAQ 不生效 | 索引未更新、读取路径不对或旧版本片段未删除 |
| 切换向量模型后报维度错 | 文档向量与查询模型不一致，重建索引 |
| 切换模型同维度也效果差 | 不同模型的空间未必可比，仍应重建 |
| 教学向量找不到同义句 | 六类手工特征有限，没有真实语义学习 |
| 回答有来源但结论错 | 来源存在不等于支持结论，需要逐条核查 |
| 验证损失下降但业务变差 | 验证集太小/泄漏/不代表实际任务，要独立人工评测 |
| SFT 数据集加载失败 | 检查 JSONL 每行结构、角色、编码以及实际训练库 Schema |
| “加资料”后以为已微调 | 检查是否真的执行参数梯度更新；RAG 建索引不是模型训练 |

### 11.6 正确报告一个问题

给别人排查时提供：执行命令、Python/依赖版本、离线或在线模式、完整的安全错误类别、最小合成输入、预期与实际行为。不要附 API Key、授权码、其他用户代码或生产数据库连接。

例如：“Windows/Python 3.12；离线 lab06；start task-a 正常，但 status 使用 task-b 提示不存在。”这个信息足以解释 task ID 不一致。相比截图一句“不能用”，最小复现更有效。
