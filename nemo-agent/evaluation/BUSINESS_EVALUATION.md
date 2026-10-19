# NemoOJ Agent 业务评测使用说明

这套工具回答三个问题：Agent 是否完成了任务；完成过程是否遵守边界；结果的质量、耗时和成本是否值得接受。

所有命令从仓库根目录运行。工具不改变生产工作流，不连接生产数据库、RabbitMQ 或 SMTP，不自动发布题目、不替用户操作账号。真实模型调用需要显式开启。

## 1. 数据集与评测层次

| 数据 | 覆盖范围 | 能证明什么 |
|---|---|---|
| private-generation-v1.json | 18 个固定契约，360 组输入/期望答案 | 固定算法契约、序列化、独立答案检查；原有后端测试和沙箱脚本继续使用 |
| business-v1.jsonl | 60 个业务请求与预期约束 | 对不同工作流进行统一检查，保留人工评分规则 |
| tests/test_evaluation.py | 评分器和 CLI 自身的正反例 | 评分不会跳过失败、接受过期复核或把 Mock 当真实质量 |

60 条业务样本包含：代码分析 20、私人题生成 12、计划 8、网站助手 12、反馈 4、维护建议 4。覆盖三语言、CE/RE/TLE/WA/AC、系统故障、诊断不足、注入文本、代码答疑隔离、红黑树三个难度、自定义节点、已完成计划项、人工接管等。

这些都是原创合成数据，不含真实用户资料或提交。41 条 regression 用于日常调整；19 条 holdout 留作验收，不根据其结果反复修改提示词。默认只选择 regression；`--split all` 会合并两组。小数据集不代表所有真实需求，真实失败应匿名化并经人工标注后加入新版本。

三种运行来源必须分开解释：

1. **mock**：实际执行 LangGraph，但模型使用固定响应。只验证评测程序和流程可运行；质量始终 `NOT_MEASURED`。分类失败通常是固定 Mock 没有业务推理能力，不是线上模型得分。
2. **live / graph_component_no_sandbox**：使用真实供应商 API 执行本地 Graph 组件。包含模型调用、Schema 解析、计划规范化等，但不包含生产队列、配额、租约、缓存或判题沙箱。私人题走无验证客户端的兼容生成分支，不冒充线上分阶段生成流程，也不据此声称线上生成加速。
3. **recorded / recorded_e2e**：导出请求给独立验收适配器，通过正常业务链路运行后导入实际输出、轨迹、账本及沙箱证据。导入程序检查绑定和完整性，但无法验证一份手工填写的证据是否真实；应保留原始任务/验证日志供审计。

本工具不内置模型自评，不把 Mock/未经验证的参考解当 oracle，也不会为了让报表变绿伪造沙箱成功。

## 2. 安装与首次离线运行

已有本项目环境可直接使用：

```powershell
$env:PYTHONPATH = "$PWD/nemo-agent"
$py = "$PWD/build/generation-venv/Scripts/python.exe"
& $py -m nemo_agent.evaluation check --split all
& $py -m nemo_agent.evaluation run --mode mock --split all --out build/eval-demo-01
```

新环境（Python 3.12）可以先执行 `python -m venv .venv`，再执行 `./.venv/Scripts/python.exe -m pip install -e './nemo-agent[test]'`，并将 `$py` 改成该解释器。

输出目录必须是新目录，避免覆盖历史证据。生成：

- manifest.json：数据集/评分器/Agent 源码指纹、SDK 版本、模型名、参数、样本选择和重复次数。
- responses.jsonl：实际输出、节点名称、耗时及每次模型调用计费汇总；异常只保存类型，不保存供应商异常正文。
- report.json、report.md：总览、分任务与缓存状态统计、逐样本检查结果、重复运行稳定性。
- reviews-template.jsonl：待人工填写的评分表，绑定具体输出 SHA256。

`check` 不运行 Graph；`run --mode mock` 不发出模型网络请求。可用 `--task ANALYSIS` 等参数选择一种或多种功能，`--limit 3` 取筛选后的前 3 条，`--repetitions 3` 重复执行，每条最多 5 次。固定采样条件不保证外部模型完全确定性。

## 3. 如何解读结果

每条样本有四种结果：passed、failed、pending、skipped。超时属于失败；未运行或因预算不足而未开始的样本是 skipped，仍留在预期运行次数和通过率分母里。

整体结论：

- NOT_MEASURED：Mock，不能报告真实模型准确率。
- FAIL：至少有一条失败；同时仍可能存在待复核/缺失样本，应看明细。
- INCOMPLETE：没有失败，但有人工作业、沙箱证据或运行记录缺失。
- PASS：当前选择的所有重复样本均满足自动检查和所需证据。

通过率 = passed / 预期样本运行次数。它只代表当前固定集与规则，不代表真实系统保证。

自动检查包括：输出 Schema、节点/结果通道、判题结果与源码行号、可核验复杂度、计划题号/覆盖天数/完成项/标签、网站助手分流与相关条目、FAQ 分流和人工接管。合成隐私标记如果进入模型输入会在请求前阻断；出现在结果里也算严重失败。

网站助手和反馈的固定分类可自动打分；开放式内容仍需人工判断。Schema 通过只意味着格式有效，尤其不能证明代码分析正确或生成题目符合目标。

## 4. 人工复核

代码分析、生成题目、计划和维护建议各有 4 个标准，具体检查要点保存在每条样本的 reference_notes。评分规则：

| 分数 | 含义 |
|---|---|
| 0 | 错误、无证据、关键要求未满足 |
| 1 | 部分满足，存在可指出的缺陷 |
| 2 | 满足要求，有明确依据 |

初版通过要求：每项至少 1 分，均分至少 1.5/2。这个阈值是项目初始规则，应结合人工反馈再调整并重新建立基线。建议对关键样本安排两人盲评，记录分歧。维护建议必须保留原题契约；代码分析不得猜测隐藏用例或把系统故障归因于用户。

复制 reviews-template.jsonl 为单独文件，填写 reviewer、scores 和 comment。不要修改 caseId、repetition、outputSha256。空评分、布尔值代替分数、不完整项目或过期输出哈希不能通过。

例如一次真实 ANALYSIS 运行使用 `--task ANALYSIS --limit 3`，复核后的重评分命令为：

```powershell
& $py -m nemo_agent.evaluation score --task ANALYSIS --limit 3 `
  --responses build/analysis-live-01/responses.jsonl `
  --source-manifest build/analysis-live-01/manifest.json `
  --reviews build/analysis-live-01/reviews-filled.jsonl `
  --out build/analysis-reviewed-01 --require-pass
```

筛选、split、重复次数需与原运行一致；评分器或数据集变化后不能直接复用旧 manifest。Mock 经人工重评分仍保持 Mock 来源，不会变成真实模型成绩。

## 5. 私人题沙箱证据

支持历史六份 ACM/FUNCTION 参考代码格式，以及当前 CANONICAL_LINES_V1 的三份核心参考解格式；后者的 ACM 包装由现有系统生成。

私人题缺少真实沙箱/独立算法证据时，即使 Schema、人工评分均通过，也只能 pending。将实际验证证明附加到 responses.jsonl 的对应行：

```json
{
  "validation": {
    "outputSha256": "与该行完整output对应的SHA256",
    "scope": "sandbox",
    "simulated": false,
    "passed": true,
    "combinations": ["java:ACM", "java:FUNCTION", "python:ACM", "python:FUNCTION", "cpp:ACM", "cpp:FUNCTION"],
    "oracleValidated": true
  }
}
```

这只是字段示例，不是可提交的证据。哈希使用 `nemo_agent.evaluation.data.digest(output)` 计算，不能用原始 JSON 文件字节哈希代替。原始验证记录必须对应同一份候选/修订，独立算法应独立编写并人工审核，不能把生成解复制为 oracle。真实用例强度还需边界输入、性质检查和错误解检测；这些由人工 test_strength 与已有验证服务共同承担。

只证明两组实现相互一致仍不证明题意正确。题意匹配、难度和边界依然需要复核。原有固定集脚本可作为沙箱基线，但它不是新生成题目的证明。

## 6. 显式开启真实模型组件评测

先小批运行，再决定是否扩大。PowerShell 7 中可隐藏输入临时密钥：

```powershell
$env:NEMO_EVAL_API_KEY = Read-Host '评测专用 API Key' -MaskInput
$evalModel = Read-Host '模型 ID'
$evalEndpoint = Read-Host 'OpenAI 兼容 HTTPS 地址（不含密钥）'
$evalInputPrice = Read-Host '实际输入价格：USD/百万 token'
$evalOutputPrice = Read-Host '实际输出价格：USD/百万 token'

try {
  & $py -m nemo_agent.evaluation run --mode live --allow-model-calls `
    --task SITE_HELP --limit 2 --repetitions 2 `
    --model $evalModel --base-url $evalEndpoint `
    --max-calls 4 --max-cost-usd 0.10 `
    --input-price $evalInputPrice --output-price $evalOutputPrice `
    --out build/help-live-01
} finally {
  Remove-Item Env:NEMO_EVAL_API_KEY -ErrorAction SilentlyContinue
}
```

不会读取生产 env 文件、SSH 密钥或自动使用生产模型密钥。必须同时具备 live、显式开关、评测专用环境密钥、模型/HTTPS 地址、正数调用/成本上限及两种价格。供应商 SDK 不自动重试；Graph 自身的生成格式重试也消耗相同总调用预算。

费用采用你提供的 USD 价格估算；每次请求前预留输出上限和保守的输入/工具 Schema 开销。超时/缺失 usage 不能证明免费，因此保留未知计费预留；已发生的失败调用同样统计。预留不够会停止后续请求，剩余样本保留为 skipped。

这不是供应商账单的硬限额：错误价格、供应商计费规则或超出预估的用量都可能影响金额，请结合供应商端硬配额。未知 usage 会导致 costComplete=false，此时不计算完整单次成功成本。建议使用独立低额度 key。

私人题组件评测通常需要更大的 `--max-output-tokens`（例如 12000）和相应费用预算；其耗时不含生产沙箱，不能用于报告网站端到端生成速度。

## 7. 导出真实业务链路验收请求

```powershell
& $py -m nemo_agent.evaluation export --task PRIVATE_PROBLEM --split all `
  --repetitions 2 --out build/generation-e2e-requests
```

requests.jsonl 只含请求上下文和样本标识，不包含标准评分答案；manifest 标记 recorded_e2e。由独立验收适配器在隔离环境通过正常业务接口运行，记录 caseId/repetition 到真实任务 ID 的映射。样本中的 ownerUserId/submissionId 是占位数据，不能直接提交生产内部接口；代码分析验收需创建对应的隔离测试提交。导出本身不产生任何任务或费用。

每行验收结果需要：caseId、repetition、status（completed/error/skipped）、output（保留与 Graph 相同的结果键）、trace（实际节点名）、elapsedMs、cacheState（cold/warm/unknown）、usage 和私人题 validation。失败同样必须导出，不允许只交成功行。usage 可包含 calls、inputTokens、outputTokens、unknownCalls、costUsd、knownCostUsd；未知总费用用 null，不能填 0。

然后用 `score` 配合同一份导出 manifest 和筛选参数评分。后端权限、数据库副作用、邮件接管、账本幂等及恢复能力还须沿用既有集成验收脚本检查；单独的模型输出评分无法证明这些事项。

## 8. 版本比较与验收建议

```powershell
& $py -m nemo_agent.evaluation compare `
  build/baseline-reviewed/report.json build/candidate-reviewed/report.json `
  --fail-on-regression
```

要求同一数据集、评分器、样本选择、重复次数、运行来源、评测范围和逐样本缓存条件；否则拒绝比较。模型和 Agent 源码指纹允许不同，以便观察迭代效果。Mock 不参与质量提升比较。对比列出原来通过而现在未通过的样本、严重失败变化、通过率差、P95 差和账本完整时的成本差；证据未补齐不能作为完整发布结论。

初期建议：先跑全部离线检查；真实模型选每类少量代表样本，重复 2–3 次；人工复核后建立首份真实基线；最后运行留出集和生产式故障/权限验收。任何一次严重越权或隐私泄漏都应阻止发布，平均分不能抵消。首次通过率、修复后通过率、首题/三题全部可用时间应从真实任务各阶段记录计算，当前本地组件报告不伪造这些指标。

## 9. CI 与日常维护

新增 GitHub Actions `agent-evaluation.yml`：校验数据、运行评分器回归、离线执行全部 60 条、核对操作覆盖率，并保存报告。CI 无模型密钥，不发起付费评测。只有 workflow 文件落地，远端 CI 是否通过应以 GitHub 实际运行结果为准。

升级评分规则、数据或契约需保留旧证据，重新建立基线；不要原地随机重采样后比较得分。生成的原始结果和人工注释请存放 build 或 evaluation/runs（已忽略）中，提交代码时不包含密钥和真实用户资料。
