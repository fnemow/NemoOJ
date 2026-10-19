# NemoOJ Agent 与 Workflow 学习包

从 [完整教程](C:/Users/nemo/Desktop/NemoOJ/record/tutorials/AgentWorkflowTutorial.md) 开始。教程包含 LangChain、LangGraph、工具 Agent、分支/并行/人审工作流、RAG、当前 NemoOJ 源码、个人开发流程、术语、评测和微调原理。

17 个练习默认完全离线。文档里的真实模型连接需你明确选择 `--online` 并在本地填写配置；未使用历史聊天中的任何凭据。无需启动项目或云服务器。

## PowerShell 快速开始

```powershell
Set-Location "C:\Users\nemo\Desktop\NemoOJ\record\tutorials\labs"
py -3.12 -m venv .venv
& ".\.venv\Scripts\python.exe" -m pip install -r requirements-lock.txt
$script:NemoTutorialPython = (Resolve-Path ".\.venv\Scripts\python.exe").Path
function labpy { & $script:NemoTutorialPython -X utf8 @args }
labpy lab00_python.py
labpy lab01_chat.py
labpy run_all.py
labpy -m unittest discover -s tests -v
```

本机若已存在本次创建的教学 `.venv`，可从定义快捷函数开始。`labpy` 仅在当前会话有效。

CMD/Linux/macOS、模型配置、逐步代码解释与全部验收标准在完整教程中。练习 11 直接导入仓库 `nemo-agent`，因此应保留整个 NemoOJ 目录；其他练习需要教学公共文件和 `data`。

## 练习地图

| 文件 | 内容 |
| --- | --- |
| `lab00_python.py` | Python 输入、函数与异步 |
| `lab01_chat.py` | 模型与消息 |
| `lab02_chain.py` | Prompt 模板和 LCEL |
| `lab03_structured.py` | Pydantic 结构化输出 |
| `lab04_workflow.py` | 条件分支与有限修正 |
| `lab05_agent.py` | 真正执行本地查询工具的 Agent 循环 |
| `lab06_review.py` | SQLite 中断、人审、跨进程恢复 |
| `lab07_parallel.py` | 并行、reducer 和汇合 |
| `lab08_rag.py` | 关键词 RAG 与无依据分支 |
| `lab09_vector_rag.py` | 教学向量、混合检索与可选真实向量 |
| `lab10_rag_agent.py` | 检索工具 Agent |
| `lab11_nemooj_source.py` | 当前项目五种任务，使用本地模拟对象 |
| `lab12_dev_workflow.py` | 本地测试失败、有限修复、生成报告 |
| `lab13_evaluate.py` | 小型检索评测 |
| `lab14_sampling.py` | temperature/top-k/top-p 数学演示 |
| `lab15_training_data.py` | SFT 数据格式、去重与组隔离 |
| `lab16_toy_finetune.py` | 字符玩具模型的实际参数更新 |

`requirements-lock.txt` 是教学环境依赖。不要覆盖生产服务依赖。生成内容默认只写本地 `labs/outputs`；`.env`、`.venv` 与输出已忽略。没有生产部署、用户数据写入、邮件发送或真实 LLM 训练。

章节源稿：`chapters`。重建完整教程：从项目根目录运行 `record/tutorials/build_tutorial.py`。验收证据：`verification.json`、`verification-versions.json`、`unit-verification.log`。
