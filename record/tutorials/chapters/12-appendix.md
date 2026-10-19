<a id="appendix"></a>

## 附录：公共代码、数据与实际验收记录

### A.1 公共代码 common.py

每个脚本导入此文件。它只在明确选用真实模型时创建在线适配器；离线对象返回固定内容。`getpass` 用于隐藏输入。环境配置和 tracing 都是独立选择。

{{CODE:labs/common.py}}

### A.2 检索公共代码 rag_utils.py

本文件把文档准备、教学特征向量、三种检索方式集中起来。阅读时先看数据如何流动，再看打分细节。学习后可以替换其中一个检索实现，保留上层 RAG 接口。

{{CODE:labs/rag_utils.py}}

### A.3 教学数据

题库元数据是代表性合成样例，不意味着云端仅有这四题：

{{CODE:labs/data/catalog.json}}

公开帮助样例：

{{CODE:labs/data/help/judge.md}}

{{CODE:labs/data/help/submission.md}}

{{CODE:labs/data/help/ai.md}}

这些帮助是为了练习自行编写的简化资料。真实用户权限和 AI 额度由生产配置与业务规则决定，不能把教学文字当成当前云端政策。

### A.4 配置和依赖

{{CODE:labs/.env.example}}

{{CODE:labs/requirements.txt}}

为了复现本次环境，优先使用 `requirements-lock.txt`。它是本教学 venv 的冻结结果，不是生产服务 lockfile；不要用它覆盖 `nemo-agent` 的部署依赖。

### A.5 全部命令速查

在教学 labs 目录、已定义 labpy 的终端：

```text
labpy lab00_python.py
labpy lab01_chat.py
labpy lab02_chain.py
labpy lab03_structured.py
labpy lab03_structured.py --invalid
labpy lab04_workflow.py --verdict CE
labpy lab04_workflow.py --verdict AC --stream
labpy lab05_agent.py
labpy lab06_review.py start --task my-first-feedback
labpy lab06_review.py status --task my-first-feedback
labpy lab06_review.py resume --task my-first-feedback --decision approve
labpy lab07_parallel.py
labpy lab08_rag.py
labpy lab09_vector_rag.py --method hybrid
labpy lab10_rag_agent.py
labpy lab11_nemooj_source.py --type ANALYSIS
labpy lab11_nemooj_source.py --type PLAN
labpy lab11_nemooj_source.py --type FEEDBACK
labpy lab11_nemooj_source.py --type INSPECTION
labpy lab11_nemooj_source.py --type PRIVATE_PROBLEM
labpy lab12_dev_workflow.py
labpy lab13_evaluate.py
labpy lab14_sampling.py --temperature 0.5
labpy lab15_training_data.py
labpy lab16_toy_finetune.py
labpy run_all.py
labpy -m unittest discover -s tests -v
```

以上默认不调用供应商。需要真实模型的 01/02/03/05/08/09/10 才有 `--online`；09 的 `--real-embeddings` 单独控制向量 API。未实现在线选项的脚本不要强行加开关。

### A.6 本次实际验证结果

{{VERIFICATION}}

`verification.json` 保存逐项安全 stdout/stderr；`unit-verification.log` 保存测试名称和结果。验证脚本使用临时目录，绝不将本地实验写入生产数据库。

本次未进行真实供应商 API 兼容、真实 Embedding 质量、LLM 训练、云端联调或真实邮件发送测试。离线结果说明框架和教学流程可复现；在线能力要在你填入当前配置后分别验证。

### A.7 如何维护这份教程

章节源稿在 `chapters`；配套源码在 `labs`；`build_tutorial.py` 将源码嵌入最终文档。修改脚本后先跑相关测试，再重建文档，避免正文代码与脚本不同步。

从项目根目录：

```powershell
& ".\record\tutorials\labs\.venv\Scripts\python.exe" -X utf8 record/tutorials/build_tutorial.py
```

最终文档含完整代码，但不能只复制某一节到任意目录就运行；公共文件与 data 的目录结构仍要保留。配套源码是最直接的演练入口。

### A.8 官方资料与论文入口

这里只列学习入口；具体论点在各章附近也有链接。文档与 API 会变化，查阅时确认与你的依赖大版本相同。

- [LangChain overview](https://docs.langchain.com/oss/python/langchain/overview)：先了解组件与入口。
- [LangChain agents](https://docs.langchain.com/oss/python/langchain/agents)、[Tools](https://docs.langchain.com/oss/python/langchain/tools)：工具循环与工具定义。
- [Structured output](https://docs.langchain.com/oss/python/langchain/structured-output)：结构化返回。
- [LangGraph Graph API](https://docs.langchain.com/oss/python/langgraph/graph-api)：状态、节点与边。
- [Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)、[Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)：恢复与人审。
- [Retrieval](https://docs.langchain.com/oss/python/deepagents/retrieval)、[RAG](https://docs.langchain.com/oss/python/deepagents/rag)：检索增强实践。
- [Pydantic models](https://pydantic.dev/docs/validation/latest/concepts/models/)：输入输出模型与验证。
- [Python venv](https://docs.python.org/3.12/library/venv.html)：隔离学习环境。
- [DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/)：供应商工具协议，不能直接套用其他模型假设。
- [Transformers fine-tuning](https://huggingface.co/docs/transformers/training)：进阶真实模型训练。
- [MCP introduction](https://modelcontextprotocol.io/docs/2026-07-28/getting-started/intro)：标准化外部能力连接。
- [RAG 论文](https://arxiv.org/abs/2005.11401)、[ReAct 论文](https://arxiv.org/abs/2210.03629)、[CoT 论文](https://arxiv.org/abs/2201.11903)：原始研究。
- [LoRA](https://arxiv.org/abs/2106.09685)、[QLoRA](https://arxiv.org/abs/2305.14314)、[DPO](https://arxiv.org/abs/2305.18290)：微调与偏好学习进阶。
