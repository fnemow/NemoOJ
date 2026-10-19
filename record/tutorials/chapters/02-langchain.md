<a id="langchain"></a>

## 第二章：LangChain 组件与三个入门练习

### 2.1 一次模型请求由哪些部分组成

聊天接口接收一组带角色的消息。系统消息描述当前任务与约束；用户消息提供需求；AI 消息代表模型输出；工具消息承载程序执行工具后的结果。模型的 `tool_calls` 与自然语言正文是不同的结构，不能只检查 `content`。

例子：用户要求“推荐二分查找题”，模型可能首先返回空 `content` 和一个 `find_problems` 工具调用；程序执行后加入 `ToolMessage`，模型才能看到题库查询结果。空正文不一定代表调用失败。

消息类型和内容结构可查 [LangChain Messages](https://docs.langchain.com/oss/python/langchain/messages)。练习 01 先从只有系统和用户消息的情况开始。

### 2.2 练习 01：第一次模型调用

目标：理解输入消息、`.invoke()`、输出内容和 usage 的区别。

```text
labpy lab01_chat.py
```

{{CODE:labs/lab01_chat.py}}

预期输出中 `mode` 为 `offline`，`answer` 以“离线模拟”开头，`usage` 可能为空。这说明响应是固定模拟，不是模型真的算出了复杂度。

配置好第 0.6 节后，可以执行：

```text
labpy lab01_chat.py --online
```

真实输出的措辞可能不同，usage 是否完整由供应商和适配器共同决定。程序会把整次模型调用的返回消息取出，你可以观察 `answer.content` 与 `answer.usage_metadata`。

`temperature=0` 通常减少采样波动，但不能把错误事实变正确，也不能保证跨供应商、跨运行完全逐字一致。`max_tokens` 限制输出，不是请求全文长度；`timeout` 约束等待时间，不证明超时前没有产生费用。

模型接口参见 [LangChain Models](https://docs.langchain.com/oss/python/langchain/models)。本教程的封装位于附录中的 `common.py`，其中读取独立的 `TUTORIAL_*` 配置。

小作业：让问题变成“什么情况下二分查找不适用”，同时修改离线回复。再用真实模式比较结果。不要用固定离线回复评价模型能力。

### 2.3 练习 02：模板、模型和解析器串联

目标：把不断变化的题目、用户水平和问题填入同一个模板，而不是每次手工拼长字符串。

```text
labpy lab02_chain.py
```

{{CODE:labs/lab02_chain.py}}

`prompt | model | StrOutputParser()` 通过框架的组合运算形成可调用链。`|` 不是普通字符串拼接：前一组件的输出成为后一组件的输入。

这条链实际执行：

1. `ChatPromptTemplate` 用传入字典替换 `{topic}`、`{level}` 和 `{question}`。
2. ChatModel 接收构造好的消息。
3. `StrOutputParser` 从模型消息提取文本。

这一组合形式常被称为 LCEL。它适合简单组件串联；需要共享状态、条件跳转和等待人工输入时，可以使用后面的 StateGraph。

小作业：增加 `{language}`，分别用 Java、Python 和 C++ 的术语解释边界。故意不传 `language`，阅读报错，找到模板变量与调用参数的对应关系。

常见误区：模板内直接书写 JSON 的 `{}` 可能被当作模板变量。使用模板时，正文里需要保留的字面大括号通常应写作 `{{` 与 `}}`；或者把 JSON 作为一个变量值传入。

### 2.4 练习 03：让结果具有可检查的结构

前端很难可靠地从任意段落里寻找“错误原因”“优化建议”。我们可以要求模型返回固定字段，再由程序校验。

```text
labpy lab03_structured.py
labpy lab03_structured.py --invalid
```

{{CODE:labs/lab03_structured.py}}

第一条输出含 `verdict`、`summary`、`suggestions`、`uncertainty`。第二条故意提交不合法数据，预期 `rejected: true` 并列出出错字段。

这里的 `Review` 是 Pydantic 数据模型：`Literal` 限定枚举；`Field` 限定长度或列表大小；`model_validate_json()` 负责解析并校验；`model_dump()` 把验证后的对象变成普通字典。Pydantic 的校验保证形状和约束，不负责证明复杂度分析真实。参见 [Pydantic Models](https://docs.pydantic.dev/latest/concepts/models/)。

真实模式使用 `with_structured_output(..., method="function_calling", include_raw=True)`。这让适配器通过工具参数承载结果，并保留原始消息以便检查 usage 和解析情况。供应商不支持这种方式时，不能仅换一个 Prompt 就认为问题解决。

Agent 层另外可以通过 `create_agent(response_format=ToolStrategy(...))` 等策略收集结构化响应；这与直接包装模型是两个入口。本教程先学习模型包装，避免把 API 的返回结构混在一起。参见 [Structured output](https://docs.langchain.com/oss/python/langchain/structured-output)。

#### 结构校验与业务校验要分开

以下结果可能完全符合字段类型，却仍不能进入真实业务：

```json
{"problemNo":999999,"reason":"适合练习二分查找"}
```

程序还必须确认题号存在、当前上线、符合用户权限、没有重复安排。NemoOJ 的 `normalize_plan_items()` 就承担了部分这种检查；后端仍会再次验证业务状态。

小作业：增加 `evidence: list[str]` 字段，要求至少一条公开诊断依据。然后分别测试“字段缺失”“列表为空”“内容与真实诊断不一致”。前两种可由数据模型检查，第三种需要业务证据核对。

### 2.5 同步、异步与流式输出

| 调用 | 适用方式 | 返回的是什么 |
| --- | --- | --- |
| `invoke(input)` | 普通同步函数 | 完整结果 |
| `await ainvoke(input)` | 异步函数内部 | 完整结果 |
| `stream(input)` | 同步消费流 | 逐块或逐步结果 |
| `astream(input)` | 异步消费流 | 逐块或逐步结果 |

模型 token 流与图节点状态流是不同的事件。后面的 `graph.stream(..., stream_mode="updates")` 显示每个节点返回的状态更新，不保证展示模型每一个 token。Web 页面也不会因为后端用了 `stream()` 就自动更新，还需要 HTTP 流、SSE 或 WebSocket 等传输和前端处理。

源码中的 `await invoke_model(...)` 使用异步模型调用；RabbitMQ Worker 可以等待外部 API，同时让事件循环处理其他 I/O。并发任务数仍应由消费配置和资源限制控制。
