# 从零搭建 AI Agent 与 Workflow：LangChain、LangGraph 和 NemoOJ 实战

版本：1.0。编写与本地验收日期：2026-10-06。面向会一点编程、第一次学习 Agent 的读者。示例采用 Python 3.12 和 LangChain/LangGraph 1.x。

这份教程的目标是：你能亲手调用模型、定义工具、运行 Agent、编排工作流、恢复中断任务、建立小型 RAG，并能读懂 NemoOJ 的 AI 请求如何从浏览器走到模型，再回到业务数据库。

教程包含 17 个编号为 00–16 的练习。完整源文件位于 `record/tutorials/labs`，正文也收录核心代码。默认练习在本地运行固定模拟响应，不需要 API Key，不连接云端数据库。真实模型调用是单独的 `--online` 选项，使用你自己在本机填写的配置。

## 阅读导航

- [学习路线与环境准备](#start)
- [第一章：先理解模型、Agent 和 Workflow](#foundations)
- [第二章：LangChain 组件与三个入门练习](#langchain)
- [第三章：Agent 的工具调用循环](#agent)
- [第四章：LangGraph 工作流、并行和中断恢复](#langgraph)
- [第五章：RAG、向量检索和 Agentic RAG](#rag)
- [第六章：逐层阅读 NemoOJ Agent 源码](#nemooj)
- [第七章：个人开发工作流实战](#development)
- [第八章：常用术语与推理方式](#glossary)
- [第九章：评测、采样与微调练习](#evaluation)
- [第十章：综合作业与学习安排](#assignments)
- [第十一章：常见报错与排查](#troubleshooting)
- [附录：公共代码、数据、依赖与参考资料](#appendix)

<a id="start"></a>

## 学习路线与环境准备

### 0.1 推荐学习顺序

| 阶段 | 先运行的练习 | 应获得的能力 |
| --- | --- | --- |
| 第一步 | 00、01、02、03 | 看懂 Python、消息、模型调用、模板和结构化结果 |
| 第二步 | 05 | 看到模型提出工具调用、程序执行工具、模型继续回答 |
| 第三步 | 04、07、06 | 会画状态图、写分支与有限循环、并行汇合、跨进程恢复 |
| 第四步 | 08、09、10 | 会准备资料、检索、生成回答、给出来源、处理无依据问题 |
| 第五步 | 11、12 | 看懂当前 NemoOJ 源码，搭建一个本地开发验收流程 |
| 第六步 | 13、14、15、16 | 会评测检索、理解采样、准备微调数据、观察参数更新 |

第一次建议每个练习做四件事：先原样运行；找到关键输出；改一个参数；预测结果后再运行。仅复制代码跑通还不足以理解程序。

### 0.2 文件组织

```text
record/tutorials/
├── AgentWorkflowTutorial.md       本教程完整文档
├── README.md                      快速开始
├── build_tutorial.py              将章节和实战源码组成完整教程
├── chapters/                      可维护的章节源稿
├── verification.json              离线命令逐项执行证据
├── unit-verification.log          离线自动验收日志
└── labs/
    ├── requirements.txt           兼容范围
    ├── requirements-lock.txt      本次验证的精确依赖版本
    ├── .env.example               只有占位符的模型配置
    ├── common.py                  公共模型与打印工具
    ├── rag_utils.py               文档切块和检索
    ├── lab00_python.py ... lab16_toy_finetune.py
    ├── run_all.py                 一次运行全部离线练习
    ├── tests/test_labs.py          检查工具执行、恢复、分支和检索等行为
    └── data/                      本地教学题库与公开帮助样例
```

`.venv`、`.env`、生成的 `outputs/` 和 `__pycache__/` 不纳入教学源码。练习 11 需要保留整个 NemoOJ 仓库，因为它直接导入项目源码；其他练习主要依赖本教学目录。

### 0.3 Windows：使用 PowerShell 建立环境

先确认终端类型。PowerShell 的提示符通常为 `PS C:\...>`；CMD 常为 `C:\...>`。`Get-Content`、`$env:NAME`、`function` 属于 PowerShell 语法，不能原样粘贴到 CMD。

安装 Python 3.12 后在 PowerShell 执行：

```powershell
py -3.12 --version
Set-Location "C:\Users\nemo\Desktop\NemoOJ\record\tutorials\labs"
py -3.12 -m venv .venv
& ".\.venv\Scripts\python.exe" -m pip install -r requirements-lock.txt
& ".\.venv\Scripts\python.exe" -m pip check
```

预期最后显示 `No broken requirements found.`。锁定文件用于复现本次验证环境；`requirements.txt` 用于在需要时重新解析兼容范围。不要把重新解析后的新环境误当作已经验收过的环境。

接下来定义一个仅在当前 PowerShell 会话有效的快捷函数。这样后文命令可以统一写成 `labpy`，也不需要改变系统脚本执行策略：

```powershell
$script:NemoTutorialPython = (Resolve-Path ".\.venv\Scripts\python.exe").Path
function labpy { & $script:NemoTutorialPython -X utf8 @args }
labpy -c "import sys; print(sys.version); print(sys.executable)"
labpy lab00_python.py
```

输出中的 Python 路径必须指向教学目录的 `.venv`。重新打开终端后，先回到 `labs` 目录，再重新定义函数；该函数不是永久安装的命令。

如果 `py` 不存在而 `python --version` 已显示 3.12，可将建立环境的命令换成 `python -m venv .venv`。如果两个命令都不存在，先安装 Python 并重新打开终端。Python 虚拟环境的原理和平台命令见 [Python venv 文档](https://docs.python.org/3.12/library/venv.html)。

#### CMD 的等价命令

```bat
cd /d C:\Users\nemo\Desktop\NemoOJ\record\tutorials\labs
py -3.12 -m venv .venv
.venv\Scripts\python.exe -m pip install -r requirements-lock.txt
.venv\Scripts\python.exe -X utf8 lab00_python.py
```

CMD 后续将 `labpy` 换成 `.venv\Scripts\python.exe -X utf8`。这里不混用 PowerShell 的环境变量写法。

### 0.4 Linux/macOS 的等价步骤

在你自己的 NemoOJ checkout 下执行；第一行路径按实际位置替换：

```bash
cd /path/to/NemoOJ/record/tutorials/labs
python3.12 -m venv .venv
./.venv/bin/python -m pip install -r requirements-lock.txt
./.venv/bin/python -m pip check
NEMO_TUTORIAL_PYTHON="$PWD/.venv/bin/python"
labpy() { "$NEMO_TUTORIAL_PYTHON" -X utf8 "$@"; }
labpy lab00_python.py
```

本次完整命令验证是在 Windows/Python 3.12 下执行；Linux/macOS 命令按标准 venv 用法给出，未声称已逐平台运行。若平台没有某个锁定版本的 wheel，阅读报错后在新虚拟环境用 `requirements.txt` 重新解析，并重新运行验收。

### 0.5 一次跑通全部离线练习

在 `labs` 目录执行：

```text
labpy run_all.py
labpy -m unittest discover -s tests -v
```

第一条会使用临时输出目录，逐项运行每个脚本、分支、批准/拒绝及源码任务类型；不会占用你手工练习 06 的任务编号。第二条检查实际行为。验收时会拦截非回环网络连接，Windows 事件循环自身所需的回环 socket pair 仍允许工作。

测试通过说明本教学样例在验证环境能运行，不表示真实供应商所有模型都支持工具调用，也不表示生成答案的准确率为 100%。本次实际版本、命令数与结果放在文末验收记录。

### 0.6 可选：接入真实模型

离线示例中的 `FakeListChatModel` 返回预先写好的文字；`ScriptedToolModel` 返回预先写好的工具调用。这两者没有学习和推理能力，只帮助你在不付费的情况下观察框架行为。真实模型会根据提示词生成结果，内容和调用次数可能变化。

PowerShell：

```powershell
Copy-Item -LiteralPath .env.example -Destination .env
notepad .env
```

只复制一次；如果已经有 `.env`，直接编辑，避免覆盖自己的设置。填写：

```dotenv
TUTORIAL_BASE_URL=https://api.deepseek.com
TUTORIAL_MODEL=你的控制台当前允许使用的模型标识
TUTORIAL_API_KEY=
```

模型标识从供应商控制台或当前模型列表确认，不能把营销名称、别名或聊天产品名称直接当作 API 参数。Key 可以留空，执行 `--online` 时会隐藏输入。也可以在本地 `.env` 填入 Key；该文件已被忽略，不要复制到教程、截图、公开仓库或聊天记录。

```text
labpy lab01_chat.py --online
labpy lab02_chain.py --online
labpy lab03_structured.py --online
labpy lab05_agent.py --online
```

按顺序尝试。01 只要求聊天能力；03 要求工具调用形式的结构化输出；05 要求真实工具调用循环。通过 01 并不能证明 03/05 兼容。默认单次输出上限 1,200 token、超时 45 秒、SDK 自动重试为 0；Agent 的循环可能产生多次模型调用。

`ChatOpenAI` 是 LangChain 的适配器类名。配置 OpenAI-compatible 的地址后，请求会发送到该地址，不意味着你在使用 OpenAI 的模型。教程使用 Chat Completions 适配路径，不尝试供应商所有 API 格式。

供应商扩展参数只能按其文档设置。例如项目现有 DeepSeek 适配曾为了结构化调用兼容性显式关闭 thinking，教学配置可以按需设置：

```dotenv
TUTORIAL_EXTRA_BODY={"thinking":{"type":"disabled"}}
```

这不是所有供应商通用的参数，也不表示思考模式都不支持工具。DeepSeek 官方工具调用说明包含 thinking 和 non-thinking 两种路径，具体限制随模型及 API 格式变化。参考 [DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/)。

Embedding 使用另一组 `TUTORIAL_EMBED_*` 配置。聊天 API 支持工具调用，不代表提供 Embedding API。本教程不假定 DeepSeek 的聊天地址可生成向量。

### 0.7 开始前先记住这几个边界

本文直接使用的资料是教学目录内的合成题库、公开帮助样例和本地源码。不会给练习 Agent 云端凭据、管理账号、生产数据库或 Docker socket。

普通教学模型响应标记为模拟；手工构造的向量标记为教学特征；微调的纯 Python 小模型标记为玩具模型。源码章节以当前代码为准，旧设计文档用作背景。教程没有部署修改，也不会改变备案期间的登录、注册关闭状态。
