# AI 训练新版部署与运行

## 发布内容

新版沿用两台服务器：核心 OJ 保存业务数据、运行验证和判题；AI 主机运行 LangGraph、调用远程模型并保存检查点。公共注册维持关闭。本次不删除旧混合计划和私人题，不安排额外备份。

迁移 `migration/V21__ai_training_batches_validation.sql` 增加批次、固定难度槽位、持久化验证任务，以及任务修复次数。先核对最高迁移版本并应用 V21，再部署 question、judge 和 Agent，最后发布前端。已有迁移不可再次直接运行 `ALTER TABLE`。

## 开关和并发

| 配置 | 所在服务 | 默认值 | 用途 |
| --- | --- | --- | --- |
| AI_CATALOG_PLANS_ENABLED | question | true | 接受新版 CATALOG_V2 计划创建 |
| AI_PRIVATE_BATCHES_ENABLED | question | true | 接受三难度批次的新建和失败槽位重试 |
| AI_AUTO_REPAIR_ENABLED | Agent | true | 自动业务修复，最多两轮 |
| AI_CANONICAL_GENERATION_ENABLED | Agent | true | 新私人题采用独立暴力校验、程序派生答案及平台 ACM 包装 |
| AI_TRUSTED_TEMPLATES_ENABLED | Agent | true | 优先选择已审计模板；目标不匹配时进入原自由生成流程 |
| AI_MODEL_CONCURRENCY | Agent | 2 | 同一进程实际模型调用并发 |
| AI_PRIVATE_VALIDATION_COMBINATION_CONCURRENCY | judge | 2 | 一份完整验证内部组合并发，限制 1–2 |
| AI_MODEL_MAX_CALLS_PER_TASK | question | 4 | 每题所有格式重试、修复共享的模型调用上限 |

4 GB 核心机若后台沙箱上限为 1，`AI_PRIVATE_VALIDATION_COMBINATION_CONCURRENCY` 也应设置为 1；否则同时发起的另一个组合会被拒绝，造成容量暂停和证据恢复。增加后台槽位前必须重新做普通判题混合负载验收。

沙箱执行引擎的 `sandbox.docker.shared-client` 默认 true，只复用 Docker 控制连接；执行容器、工作目录与输入仍隔离。每次执行必须关闭统计流。后台验证不建立持续 stats 流，改为退出后合并读取 cgroup v2 `memory.events` 与 `memory.peak`；普通提交保留采样并补充峰值。部署前需确认运行内核支持 `memory.peak`。可设 `SANDBOX_DOCKER_SHARED_CLIENT=false` 停止控制连接复用；回滚整个引擎用保留的旧镜像，并先排空任务。健康检查以应用 `/actuator/health/readiness` 为准，不能将缺少 Docker Health 配置当作服务故障。

4 GB 云端叠加层为可信沙箱服务只读挂载 `/sys/fs/cgroup:/host-cgroup:ro`，并设置 `SANDBOX_DOCKER_HOST_CGROUP_ROOT=/host-cgroup`。rootful Docker 使用 systemd 驱动且运行于 cgroup v2 时，按完整 Docker 容器 ID 读取 `system.slice/docker-<id>.scope/memory.events` 与 `memory.peak`，省去资源采集辅助进程。提交代码的容器不得挂载此目录，仍只挂载各自 `/app` 与 `/tmp`。不同驱动、缺失计数、权限不足或容器 cgroup 已移除时自动回退到原 Docker exec 读取；计数缺失不能伪造成功证据。将环境值设为空可停用快速路径。新增 `container_create`、`container_start`、`oom_host_baseline` / `oom_exec_baseline`、`memory_host_snapshot` / `memory_exec_snapshot` 低基数阶段指标；`memory_snapshot` 总指标继续保留。停止或启动失败的容器也必须回收。指标嵌套计时不可直接加总成端到端耗时。

模型调用全局并发按当前单实例部署理解。扩展多个 Agent 实例前需要共享限流，不能把进程内 semaphore 当作跨实例上限。后台完整验证当前全局一个，内部最多两个独立沙箱请求，不复用用户代码容器。2026-10-11 资源调度版本取消等待 60 秒后绕过正式队列的规则：正式提交未完成时不派发新的验证任务。后台请求在编译前和每个用例容器创建前检查正式提交与交互试运行：有前台任务等待或执行时归还槽位，待其完成及空闲宽限期结束后继续原工作目录；正在运行的容器正常结束，不被抢占。让位等待也有期限，中断后不会多归还许可。实际 CPU/内存影响需压测。

关闭创建开关仍能读取已有内容。自动修复开关由 Agent 启动读取，变更需等待任务终态再重建该容器；其余消费者继续处理旧任务。保留旧镜像回滚，V21 加法迁移保留，不删除新表。

## 队列和私有代理

新增 analysis、plans、private 三个 durable 路由/队列；保留旧 `nemo.ai.tasks` 兼容队列。消费者分别分配槽位，私人生成两个，其余各一个，prefetch=1；所有槽位共享模型并发上限。

更新 `haproxy/ai-link.cfg` 和公共库 `CsrfTokenFilter` 的精确内部回调路径：claim、context、complete、fail、heartbeat、validations、validations/{jobId}、calls/{callId}/start 或 finish。所有请求仍须 POST、HMAC 验签及任务/租约校验。只通过 WireGuard 地址暴露，公网代理继续禁止内部接口。不得简单放行所有 `/internal/**`。

HAProxy 配置先用容器内 `haproxy -c -f /usr/local/etc/haproxy/haproxy.cfg` 检查，再平滑重载。仅更新应用而忘记私有代理会让新增端点返回 HTML 403。未签名请求进入应用后仍返回无权限，不能以关闭全部 CSRF 或验签解决。

## 可靠性和费用

每批三个子任务在一个事务内创建，各自预占预算；容量、VIP 或预算不足时整体回滚。验证任务独立持久化，不在发布事务中等待沙箱；每个任务保存 revision、draftHash、结果和租约。业务修复最多两轮，模型最多四次，实际开始后 15 分钟总期限；平台验证失败在原验证任务退避重试，不调用模型修改题意。

跨云签名接口的连接/读取超时、HTTP 429/5xx 与业务系统错误单独归为暂时故障，Worker 保留检查点和预占记录，通过消息及租约重新接续，不能将其标成候选题错误。预算或权限拒绝不自动重试成网络请求，供应商调用不能绕开原有调用账本。

通过组合的缓存仅接受不可变运行时镜像摘要，绑定完整规格、用例、参考源和配置，最多 512 项、15 分钟有效。缓存复用必须注明，性能比较使用不同候选标题避免缓存命中。

用 `ai_task`、`ai_model_call`、`ai_usage`、`private_validation_job` 和 LangGraph 检查点排查阶段、调用次数和费用。不要公开候选草稿、隐藏用例、参考实现或模型密钥。用户看到阶段、修复轮数和真实已通过组合数；不可用题不能提交。

## 发布后检查

1. 普通账户不能创建、重试；本人可查询，其他用户不能读取/提交私人内容。
2. 有效标签计划覆盖所选主题，重复请求复用任务；未知/难度标签创建前拒绝且没有模型费用。
3. 三题分别交付，失败槽位重试不改变已成功题 ID/版本和提交。
4. READY 对应三语言 × 两模式的完整证明；六份参考实现真实提交均 Accepted。
5. Agent、验证进程重启和断连恢复后，确认没有重复版本、计费或过期候选发布。
6. 队列清空，验证任务进入终态，无残留沙箱容器；测量普通判题 P95、资源和费用，不仅检查页面。

性能记录必须包含失败样本。固定沙箱用例的验证加速不能替代模型生成批次的成功率与耗时验收。

## 私人题独立答案校验

新版 Agent、model DTO、question 和 judge 必须配套部署。先排空 AI、私人验证和正式提交，更新核心服务，再更新 Agent；该独立答案校验版本本身无需新数据库迁移，后续持久化断点版本需要 V22。生成候选只有三份 FUNCTION 参考解、独立 Python 校验解和 20 组输入，不能用旧版服务把它直接发布。question/judge 验证并物化六份参考解及答案，发布绑定完整规范化草稿哈希。

`AI_CANONICAL_GENERATION_ENABLED=false` 只改变新任务选择；已经保存的新版检查点仍按其原候选继续。回滚旧镜像前等待所有新版任务终态，避免旧服务不认识新协议。服务端 Runner 不携带预期答案，禁止为了调试把答案或参考实现加入用户响应。

前一轮实测结果及局限见 [私人题生成可靠性记录](../record/log/2026-10-10-private-generation-reliability.md)。2026-10-11 已补充六种可信模板、合法输入生成器和性质校验，具体覆盖范围与验收见 [模板优化记录](../record/log/2026-10-11-ai-generation-template-optimization.md)。尚未覆盖的主题仍采用自由生成，不能把六种模板的通过率推广到所有题目。

## 可信模板与执行证据复用

当前 `trusted-v1` 覆盖单次区间和、多次区间和、二维矩形和、首次目标索引、左右边界、行列有序矩阵第 k 小，共六种模板。模型根据目标及固定难度目录选择模板并给出短名称；固定签名、返回规则、题面约束、三语言核心参考解和独立暴力解由平台提供。程序生成 20 组合法输入，含两个公开样例，其余保持隐藏；同一任务可重建相同输入，不同任务使用不同种子。

不匹配的学习目标返回空选择，继续原自由生成流程，不把用户请求替换成无关模板。自由生成会多一次短选择调用，仍按原费用账本和最多四次调用限制执行。首版模板输入范围较小，适合功能与边界校验，不代表已经实现大规模复杂度判定。其他专题、丰富叙事和大数据测试后续扩展。

judge 在运行前检查输入、签名及版本，在双算法输出一致后再执行平台独立性质检查。question 发布必须同时持有匹配模板版本的性质证明、独立算法证明、六组合证明和完整候选哈希。模板源代码异常直接失败，不调用模型修改已审计模板；基础设施故障仍按现有验证任务重试。

Python 优化解与暴力解使用现有两个后台槽位并行执行；并发配置为 1 时串行。派生答案时已经执行过的 Python FUNCTION 可在**本次请求**的最终判定中复用，但仍重新比对最终答案，保留资源统计。证据绑定完整执行计划（源文件、Runner、SDK、输入、运行限制等）及不可变镜像摘要；计划变化或镜像标签可变时重新执行。没有跨任务证据复用，也没有容器复用。

运行指标增加 `pythonEvidenceReused`、`answerDerivationMillis`、`propertiesValidated`、`templateId`、`templateVersion`，保留原六组合耗时和模型账本。数据不进入公开题库；该模板版本本身无新增 HTTP 接口或数据库迁移，后续持久化断点版本新增内部接口和 V22。公开注册继续关闭。

设置 `AI_TRUSTED_TEMPLATES_ENABLED=false` 并排空后重建 Agent，可停止新任务选择模板；已保存的模板候选继续原验证，不重新生成。回滚 judge/question 必须先排空新版任务并保留已发布版本与调用账本；上一轮镜像仍保留。发布脚本只替换本次 class/Agent 文件，并核对其他 jar 内容和 compose 配置不变。本次未创建备份。

## 后台资源调度与持久化执行断点（V22）

先排空任务，应用 `migration/V22__private_validation_checkpoint.sql`，再更新 sandbox、question、judge。model 与 service-client 的新 DTO/Feign 接口必须一起进入 question 和 judge 的 jar。数据库由 question 服务负责，judge 通过受内部凭据保护的接口读取及保存证据；不能给 judge 添加数据库依赖来绕过服务边界。公网及 AI 跨云代理均不开放该接口，Agent 无需升级。

| 配置 | 服务 | 默认值 | 含义 |
| --- | --- | --- | --- |
| SANDBOX_BACKGROUND_IDLE_GRACE_MS | sandbox | 1000 | 前台结束后等待 1 秒再恢复后台容器 |
| SANDBOX_BACKGROUND_MAX_CONCURRENT | sandbox | 2 | 后台请求最大并发；总槽位仍采用原配置 |
| SANDBOX_BACKGROUND_MIN_AVAILABLE_MEMORY_KB | sandbox | 524288 | 主机可用内存不足 512 MiB 时暂停后台；512 MiB–1 GiB 时降为一个后台请求 |
| SANDBOX_FOREGROUND_BACKGROUND_WAIT_MS | sandbox | 15000 | 正式提交到达时已有后台任务占槽位，最多等待 15 秒；其他饱和情况保持原 3 秒 |

`compose.core-4g-preview.yml` 在 4 GB 主机上把后台并发默认收紧为 **1**，可通过同名环境变量显式覆盖。通用 sandbox 上限仍为 2。两请求混合实测出现正式提交容量重试，不能在这台小内存主机上直接沿用通用值；先按资源竞争报告中的一请求配置验收。组合并发仍最多两个，未获准的沙箱请求进入 CAPACITY 等待并保留其他已完成证据。

后台长编译不能被容器间让位打断，3 秒等待可能使正式提交在尚未执行用户代码前被误归为基础设施重试。新版针对到达时已有后台任务的正式请求延长有界等待，保持消费者及沙箱槽位不变；排队时不启动额外容器，不改变用户代码时间限制。超过等待期限仍明确拒绝，不能把该设置当作无限队列。

优先级为正式提交、交互试运行、后台验证、健康探测。运行中的程序不被抢占，后台在容器边界让位；后台编译采用 CPU shares=128，普通编译和所有运行容器仍为 1024，CPU quota、内存、swap 和用例时限不变。shares 是竞争时的相对权重，不能当作独占 CPU 或固定资源比例。

沙箱容量不足返回 CAPACITY 等待状态，同一验证任务 3 秒后重新排队，不消耗基础设施失败次数或模型修复预算。15 分钟任务总期限仍有效，持续前台高负载可能使私人题验证无法在期限内完成。

`private_validation_checkpoint` 通常按验证任务 ID、完整执行计划 SHA-256 和不可变运行时镜像摘要保存成功执行的输出及资源统计，20 分钟过期，定时分批清理。只有当前有效验证租约能写入，重复写入不能覆盖已有证据。服务重启或任务重试后恢复已完成执行；输入、参考源、Runner、运行限制或镜像摘要改变时必须重新执行。每次恢复仍重新比较答案并执行性质与六组合门禁；禁止跨 AI 任务或用户复用。

2026-10-13 新增十二种 `trusted-v2` 契约，覆盖字符串、动态规划、链表和二叉树的三个难度；原六种 `trusted-v1` 继续兼容。模型只能选择符合目标与难度的目录项，不可改写平台参考实现、用例生成器与性质检查。必须先升级 judge 的契约检查、question 的版本门禁，再启用 Agent 新目录，防止旧门禁拒绝新版本。固定评测数据集见 `nemo-agent/evaluation/private-generation-v1.json`。

自由生成新任务默认采用 Python 预检：一次模型调用冻结规格、输入和两份独立 Python 算法，预检通过后只生成 Java/C++，再执行完整六组合验证。模板命中任务直接完整验证，不增加预检。协议 `CANONICAL_PYTHON_PREFLIGHT_V1` 仅用于内部预检，返回 `runtimeValidated=false`、`pythonPreflightValidated=true`，不得用来发布；发布证据必须来自最新完整验证，包含全部六组合。

完整验证可以复用**同一 AI 任务**内已成功预检的 Python 输出，前提是完整冻结候选摘要、Python 与 Oracle 源码、输入、Runner、限制和不可变镜像执行摘要均匹配，当前完整验证任务与父任务租约有效。Java/C++ 实现和内部协议可在预检后补充，其他候选字段不允许改变；结果仍重新比较，不复用容器。失败预检不提供跨修订复用资格。

沿用最多四次模型调用与三份验证修订：目录选择 + 规格/Python + Java/C++ + 最多一次局部修复。若预检先修复，则没有第二次完整验证修复预算；失败调用和恢复重试也受平台调用账本上限约束。`AI_PYTHON_PREFLIGHT_ENABLED=false` 可令新自由生成任务回到原分阶段路径；已有 LangGraph 检查点继续恢复其原节点。未启用事件等待时仍每三秒轮询。

部署先更新 judge 预检处理，再更新 question 不可发布证据、转段绑定与检查点读取，完成签名接口负面测试后启用 Agent。无需数据库迁移。回滚旧镜像前排空全部新任务，不能让旧版本处理半途的预检检查点；保留旧镜像，注册开关、判题并发、沙箱槽位和模型配置不变。

证据包含隐藏输出，不得暴露给普通用户或写入公开性能报告。新增低基数指标 `nemo_ai_private_validation_capacity_pauses_total` 与 `nemo_ai_private_validation_checkpoint_restores_total`，验证 ID 仅用于日志关联。

回滚前排空新版任务，按顺序恢复原 judge、question、sandbox 镜像和原环境配置；保留 V22 加法表，不删已发布题目或模型账本。2026-10-11 发布保留旧镜像，未执行数据库备份。实际云端验收见 [资源竞争优化记录](../record/log/2026-10-11-background-resource-optimization.md)。

## 单次使用容器预取与事件等待（第五轮）

`SANDBOX_PREWARM_ENABLED` 默认 false。启用后，在一个后台验证请求内部最多提前启动一个仅运行平台 sleep 的新容器。它绑定本请求的只读工作目录，匹配镜像、CPU、内存和 PID 限制；领取一次后立即从预取状态移除，用例完成强制销毁，不返回池。编译容器、普通提交均不使用此通道。前台正在执行/等待、主机内存未知或可用内存不足 1 GiB 时不补充。未领取容器 30 秒回收，请求结束排空创建线程后再清理工作目录，异常或未就绪时冷启动降级。

后台用例先运行公开样例，再优先小规模/空边界输入，保留原始用例编号和发布顺序。RE/TLE/MLE/OLE 立即终止该实现剩余用例；正常实现仍运行全部用例。由于沙箱接口只接收输入、不接收答案，WA 与双算法不一致仍在输出收集后由 judge 判定，不能宣称所有错误均已逐用例短路。

`AI_EVENT_DRIVEN_VALIDATION_ENABLED` 默认 false，生产必须配合 PostgreSQL 检查点。开启后 LangGraph 验证节点产生持久化 interrupt，再通过已有签名 `/heartbeat` 接口发送 `stage=WAIT_VALIDATION` 和 validationId。question 核对最新验证修订与租约，保留父任务 startedAt+900 秒绝对期限，释放 worker 所有权。验证完成后在短事务中设置 VALIDATION_READY 并写入每修订唯一的 Outbox 通知；五秒扫描补偿结果先到、暂停后到与丢失通知。无需增加数据库迁移或放宽 HAProxy/CSRF 白名单。

Agent 对每次消息使用独立租约标识；恢复从 PostgreSQL 读取 interrupt，使用 Command(resume=True)，重放同一候选/修订的幂等提交，再读取服务端证据，通知消息本身不是证明。正常恢复不增加 attemptCount；过期恢复由原租约回收器处理并结算预算，不延长总截止时间。没有检查点或关闭事件开关时继续轮询降级。已有中断任务仍识别恢复状态；回退旧代码镜像前必须先排空此轮任务。

部署先更新 sandbox 并复测，再更新 judge 和 question，完成协议负面验收后启用 Agent；两个开关可分别关闭，普通判题与模型并发不变。第五轮云端另设置 `SANDBOX_BACKGROUND_IDLE_GRACE_MS=5000`：前台持续提交时优先保护正式判题，后台候选可能等到流量停止后才执行，不能保证高负载下仍为五十秒。旧镜像保留，不创建数据库备份。参考 `ops/validation_round5_release.py`、`ops/validation_round5_agent_release.py`、`ops/round5_background_gate.py`，实际效果与局限见第五轮验收记录。
