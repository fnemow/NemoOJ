# 多语言判题扩展方案

日期：2026-09-17；实施更新：2026-09-19。状态：三语言 ACM/FUNCTION 已实现；真实 Linux Docker/GCC 14 的 ACM 15 项、FUNCTION 42 组、完整 MQ 落库链路 6 组，以及隔离、8 请求并发、故障和固定摘要启动验收已通过。前端联调、生产容量/硬配额、镜像扫描及完整发布验收仍未完成，阶段 D 不标记全部通过。实测记录见 `record/log/2026-09-19-02-00-multilanguage-container-validation.md`，运行步骤和已知部署限制见 `deploy/runtimes/README.md`。

## 1. 使用目的与范围

在现有 Java 判题基础上增加 Python、C++，三种语言均支持 ACM（完整程序、标准输入输出）与 FUNCTION（Solution 类、指定方法、系统生成 Runner）。分阶段实现，全部验收通过后再将本次多语言目标标记为完成。

用户已确认的基线：

| API 语言标识 | 展示名称 | 运行环境 | 用户可使用的库 |
|---|---|---|---|
| `java` | Java（展示镜像实际版本） | 保留现有 Java 判题环境，本次不升级语言版本 | 保持现有约定 |
| `python` | Python 3.12 | CPython 3.12，固定补丁版本与镜像 digest | Python 标准库 |
| `cpp` | C++17 | GCC 14，显式指定 `-std=c++17`，固定补丁版本与镜像 digest | C++ 标准库 |

不提供用户自选镜像、编译选项、pip/apt 安装或任意第三方依赖。GCC 自带的非标准头文件即便可用，也不作为跨环境兼容承诺。平台 Runner 的 JSON 支持依赖属于平台内部实现，不代表向用户开放第三方库。

本方案更新了 `record/pre-release-agent-plan.md` 中“首发仅 Java、不扩展语言”的范围约定：多语言部分以本文为准，原计划的 T08（沙箱）、T09（容量）等上线门槛仍需完成。此句为 2026-09-17 设计阶段范围；2026-09-18 已按后续用户请求开始开发，部署验收仍单独记录。

## 2. 代码现状与必须补齐的缺口

以下路径均相对仓库根目录；结论来自当前代码检查。

| 位置 | 当前行为 | 扩展要求 |
|---|---|---|
| `OJ-backend-model/.../QuestionSubmitLanguageEnum.java` | 已有 java/cpp/python | 保留稳定标识，不重复添加枚举 |
| question 的 `QuestionSubmitServiceImpl`、judge 的 `JudgeServiceImpl` | 读取 `judge.language.enabled`，默认 java | 增加语言、模式、题目签名的联合准入 |
| judge 的 `AcmJudgeModeAdapter` | 固定 Main.java / Main | 按语言选择文件名与入口 |
| judge 的 `FunctionJudgeModeAdapter` | 直接依赖 JavaHarnessGenerator，固定 Java SDK | 抽象 HarnessGenerator 注册表，加入 Python/C++ 实现 |
| sandbox 的 `runtime/LanguageRuntimeAdapter` 与 Registry | 已有可选编译步骤、命令参数数组、资源策略 | 增加 PythonRuntimeAdapter、CppRuntimeAdapter，拒绝重复注册 |
| sandbox 的 `JavaDockerCodeSandBox.executeCodeV2` | 只检查适配器存在；统一复制 Gson | 执行时也检查启用状态；按语言准备可信依赖 |
| sandbox 的 `DockerExecutionEngine` | 编译容器根目录只读，无可写 /tmp；运行容器才有 tmpfs | 编译与运行均配置受限临时目录，保证 g++ 可编译 |
| sandbox 的 `JavaCodeSandBoxTemplate` | 已允许 .py/.cpp 等扩展名，有文件数量、大小与路径检查 | 叠加各语言文件集合/入口约束，保留现有路径防护 |
| sandbox V2 + judge 的 `DefaultJudgeStrategy` | 沙箱乘 runtimeFactor，判题端又用题目原始限时比较 | 统一实际生效限额，避免 Python 得到补偿后仍被二次判超时 |
| question 的 `QuestionServiceImpl` | Java 标识符、关键字与类型白名单 | 提取语言无关类型规则，增加各语言签名可表达性检查 |

当前仓库未发现前端项目，因此本文定义前端对接契约；实际前端联调属于后续实施验收，不应声称已完成。

## 3. 总体结构与职责

沿用现有 V2 执行链路，不复制三套判题服务：

```text
提交接口：校验语言 × 模式 × 题目规格 → 保存提交 → MQ
  → JudgeModeAdapter
      ACM：语言文件布局
      FUNCTION：HarnessGeneratorRegistry → 对应语言 Runner + SDK
  → ExecutionPlan（语言、入口、文件、用例、资源预算、版本信息）
  → 沙箱：校验计划 → LanguageRuntimeRegistry → 编译/语法检查
  → DockerExecutionEngine：每个用例独立容器
  → 统一状态解析、输出比较、落库
```

### 3.1 判题服务

新增 `LanguageSubmissionProfile`（名称为建议）负责 ACM 文件名、入口、展示模板；新增 `HarnessGenerator` 接口与注册表，按语言选择实现。Harness 生成结果包含文件列表、入口、SDK 版本与 Harness 版本。

`FunctionJudgeModeAdapter` 保留用例与执行计划组装职责，Java 实现迁入接口，新增 Python/C++ 生成器。结果比较继续走 `JudgeServiceImpl → JudgeManager → OutputChecker` 的主链路，避免给适配器中已有的结果解释方法另写一套不一致规则。

期望答案仅留在判题服务，不写入执行计划、源码、容器或 Runner。

### 3.2 沙箱

复用 Runtime Adapter 与 Docker 执行引擎。将 Java 命名的公共工作区能力逐步提取为中性组件；首次实施优先保持现有 Bean 名和 V1 Java 行为兼容，避免仅为改名扩大变更。

适配器只能返回服务端构造的参数数组，不接受用户命令，不通过 `sh -c` 拼接执行。入口来自已验证的语言/模式模板，不能直接信任请求中的任意路径。运行时描述中的扩展名白名单必须真正参与校验。

Python/C++ 只使用 V2，FUNCTION 同样要求 V2；不得误回退至固定 Java、命令行参数输入的 V1。旧 Java V1 仅为兼容保留，其接口明确拒绝非 Java 请求。

### 3.3 能力与开关

能力集合 = 已实现的语言/模式 ∩ 部署启用集合 ∩ 当前可用运行时 ∩ 题目签名支持集合。

- question：入库、入队前拒绝不可用组合。
- judge：消费时再次检查，处理禁用后仍在队列的消息。
- sandbox：执行前检查启用语言、模式、运行时版本及文件布局；注册了 Adapter 不等于开放了语言。
- 新增沙箱内部能力接口，使用现有沙箱鉴权；包含语言、模式、runtimeId、toolchainVersion、protocolVersion、镜像就绪状态、策略版本。
- question 通过内部 judge 服务获取能力摘要，结合本地开关提供公开语言列表/题目模板接口。公开结果不包含镜像仓库地址、宿主机路径或密钥。
- 能力短时缓存；依赖不可用时仅使用仍在有效期内的已验证缓存，过期则关闭受影响语言的新提交并报告服务暂不可用，不永久缓存“支持”。多沙箱部署对可路由节点取能力交集，或按 runtimeId 精确路由。

当前默认仍只开启 Java。阶段中间态可配置 `python/cpp: [ACM]`，FUNCTION 未验收时不得显示为可用。接口应提供明确原因，前端隐藏选项不能代替后端校验。

## 4. 编译与运行契约

### 4.1 ACM

| 语言 | 源文件 | 入口 | 编译/预检查 | 运行参数示意 |
|---|---|---|---|---|
| Java | Main.java | Main | 现有 javac 流程 | 现有 java 命令 |
| Python | main.py | main.py | 可信语法检查器，仅 compile 源码、不执行 | `python3 -I -B -u -X utf8 /app/main.py` |
| C++ | main.cpp | main | `g++ -std=c++17 -O2 -pipe /app/main.cpp -o /app/main` | `/app/main` |

表格是固定参数数组的可读形式。C++ 不加 `-static`、`-march=native` 或用户附加参数，编译与运行先使用同一固定镜像以避免动态库不匹配。后续拆分编译/运行镜像需额外验证 ABI。

Python 使用镜像内可信检查脚本逐个读取 `.py` 并调用 `compile(text, filename, 'exec')`；脚本不得导入用户模块或执行模块顶层代码，不生成 .pyc。语法错误归 CE，其余执行异常归 RE。将接口注释中的“Python 一律跳过编译”更新为“编译/语法检查可选”，无需改变 Optional 接口。

CPython 的 `-I` 会排除脚本目录并忽略 PYTHON 环境变量；`-B` 禁止导入时写 .pyc。FUNCTION 因此使用绝对路径加载 solution.py，不能依赖普通 `import solution` 或 PYTHONPATH。UTF-8 使用 `-X utf8` 显式启用。[Python 命令行文档](https://docs.python.org/3.12/using/cmdline.html)

C++ 标准版本通过 `-std=c++17` 显式选择，不依赖编译器默认值。[GCC 14 选项文档](https://gcc.gnu.org/onlinedocs/gcc-14.3.0/gcc/C-Dialect-Options.html)

### 4.2 FUNCTION

| 语言 | 用户提交 | 系统生成/提供 | 入口与编译原则 |
|---|---|---|---|
| Java | Solution.java，Solution 实例方法 | Runner.java、ListNode/TreeNode、固定 Gson | 保留 Runner 入口与现有协议 |
| Python | solution.py，`class Solution` 与实例方法 | runner.py、节点 SDK | 运行 runner.py，绝对路径加载用户模块，显式注入节点类型 |
| C++ | solution.hpp，`class Solution` 与 public 方法 | runner.cpp、节点 SDK、固定 JSON 头文件 | 只编译 runner.cpp，由其 include SDK 与 solution.hpp，产物 /app/main |

用户仍通过现有 code 字段提交单份源码；服务端生成多文件，不新增任意文件上传功能。系统文件名为保留名，用户不能覆盖 Runner 或 SDK。C++ 头文件不单独作为编译单元，避免重复定义。

C++ 内部 JSON 组件建议采用固定版本的 nlohmann/json 单头文件，随受控镜像提供，构建时校验 checksum 与许可证，不在评测时下载。它仅服务平台协议，用户题解依赖政策仍是标准库。[项目官方文档](https://json.nlohmann.me/)

统一输入为一个 JSON 对象，键对应 ProblemSpec 参数名；每个测试用例启动新进程，创建新 Solution。输出沿用 `{"status":"OK","actual":...,"timeMs":...}`；`actual` 必须存在，允许显式 null。异常输出为 RUNTIME_ERROR 协议并映射 RE，不能按文本子串识别状态。

Runner 的函数耗时只作为诊断信息，资源判定使用沙箱测量值。普通调试输出重定向到有界诊断流，Python 在加载模块前重定向，C++ 同时考虑 cout 与 printf。应对超大输出、直接文件描述符写入、提前退出、无效 JSON 做明确失败处理；不能把同进程 Runner 视为阻止恶意伪造结果的安全边界。协议通道不存放答案，严格校验结构、类型、大小且不因非法输出给 AC。

### 4.3 统一类型与题目兼容

先完整覆盖现有白名单，不在本次顺带扩大 DSL：

| DSL | Python | C++17 |
|---|---|---|
| int / long | int；输入输出分别检查有符号 32/64 位范围 | int / long long |
| double | float，仅有限值 | double，仅有限值 |
| boolean | bool，拒绝以整数代替布尔值 | bool |
| string | str | std::string，UTF-8 字节序列 |
| int[]、long[]、double[]、boolean[]、string[] | 对应元素 list | 对应元素 std::vector（含 vector<bool> 专门适配） |
| int[][] | list[list[int]] | vector<vector<int>> |
| list<int> | list[int] | vector<int> |
| list_node<int> / tree_node<int> | 平台 ListNode / TreeNode | 平台 ListNode* / TreeNode* |
| void | None，结果从指定参数提取 | void，结果从指定参数提取 |

规则：

1. `resultSource=return / parameter:i / parameters:[i,j]` 全部覆盖。原地修改主要适用于数组、列表、节点；Python 对不可变标量的重新绑定不能回传，Java 基础类型同样按值传递。新增跨语言题目拒绝以不可变标量作为原地修改结果；存量此类题目先审计，不悄悄改变 Java 判题。
2. C++ 的可变数组/列表参数用引用，节点按题目模板传指针；重绑根指针不作为跨语言通用原地修改能力，需返回新根。布尔向量、空容器、null 节点分别测试。
3. 节点输入输出沿用现有数组/层序表示，统一空树、尾部 null、节点数/深度上限；检测环和超限，明确报 RE，不静默截断为看似正确的结果。
4. 字符串统一 UTF-8，不能假定 Java UTF-16、Python 字符索引与 C++ 字节索引相同；依赖字符下标语义的题目明确字符集和单位，存量题目逐题检查。
5. JSON 的 long 必须保持整数精度，禁止经 double 中转；非有限浮点值拒绝。复用现有 EXACT/TEXT/FLOAT/UNORDERED_LIST/MULTISET 比较器，针对大整数、转义、浮点 epsilon 补差分测试。
6. 新跨语言签名采用各语言都合法的标识符，排除三种语言关键字及平台保留名；内部变量用固定生成名，用户参数名只作严格转义后的 JSON 键。旧 Java 合法但 Python/C++ 不合法的题目保留 Java，能力接口给出不支持原因，修订题目版本后再开放。
7. 旧 harnessVersion=1 保持可解释；记录语言对应的生成器/SDK 版本。只有协议或题目语义改变时升级题目 Harness 版本，不覆盖已发布题目或静默重写历史数据。

## 5. 配置与资源策略

### 5.1 配置归属

建议通过 `@ConfigurationProperties` 集中绑定并校验。开关、模式矩阵、策略均由发布配置统一维护；运行时不依赖人工分别编辑三个服务。以下为待实现示例，不可直接当作当前已支持的配置：

```yaml
# question / judge
judge:
  language:
    enabled: ${JUDGE_ENABLED_LANGUAGES:java}
    modes:
      java: [ACM, FUNCTION]
      python: [ACM, FUNCTION]
      cpp: [ACM, FUNCTION]

# sandbox
sandbox:
  language:
    enabled: ${JUDGE_ENABLED_LANGUAGES:java}
    modes:
      java: [ACM, FUNCTION]
      python: [ACM, FUNCTION]
      cpp: [ACM, FUNCTION]
  image:
    java: ${SANDBOX_JAVA_IMAGE}
    python: ${SANDBOX_PYTHON_IMAGE:}
    cpp: ${SANDBOX_CPP_IMAGE:}
  auto-pull: false
  image-validation:
    enabled: true
```

生产只对启用语言强制要求非空镜像 digest；未启用的新语言可以不配置镜像。enabled 为空、未知/重复语言、已启用但无适配器、非法模式、非法资源值均启动失败。重复注册 Adapter 直接失败，不能后者覆盖前者。保留旧配置兼容读取时必须给出明确优先级，不能出现两个有效来源。

新增 `sandbox.language-policies.<language>` 绑定编译时间、编译内存、时间倍率、PID、输出上限等；部署中只允许受控配置更改，用户请求不能覆盖。

### 5.2 资源预算

以下是压测起点，不是未经验证的性能保证：

| 策略 | Java | Python | C++ |
|---|---|---|---|
| 编译/预检查超时 | 30s | 10s | 30s |
| 编译/预检查内存 | 512MiB | 256MiB | 512MiB |
| 运行时间倍率 | 1.0（保留） | 3.0（待基准题校准） | 1.0 |
| PID 上限（含容器进程/线程） | 64 | 64 | 64 |
| 运行内存 | 题目限制，未配置沿用 256MiB 默认值；平台硬上限沿用 1GiB | 同左 | 同左 |
| stdout 上限 | 默认 1MiB，题目可更低 | 同左 | 同左 |

采用单一 `EffectiveResourceLimits` 语义：沙箱按受控策略将题目基准限制转换为实际预算，返回该次执行的 effectiveLimits 与 policyVersion；judge 使用同一实际预算，不能再拿原始题目限时比较。两侧模型要同步扩展。旧响应缺少该字段仅允许在经过兼容验证的 Java 旧路径处理，不能对 Python 默认按 1 倍限时。

运行时间 `ceil(baseTimeMs × runtimeFactor)` 只计算一次，同时验证正数、乘法溢出及平台硬上限。平台时间上限、最大用例数、输入总量与整次任务期限在 T08/T09 容量预算中确定；配置越界应拒绝，而不是悄悄改变题目语义。记录原始测量时间与实际限额，不把 Python 测量时间除以倍率展示。

超时口径沿用执行启动至结束的墙钟时间，编译不计入单用例耗时；Runner 解析与序列化计入运行预算。HTTP 超时、MQ 消费处理期限、重试必须覆盖“编译预算 + 用例预算总和 + 有界容器操作开销”，不能仍用固定短超时导致正常 Python 任务被反复执行。

### 5.3 容器与工作区前置修复

- 编译、运行均非 root、无网络、只读根目录、cap-drop、no-new-privileges、PID/内存限制。工作区每次提交隔离，每例新容器。
- 编译添加受限 /tmp tmpfs 并设置 TMPDIR；/app 为可写产物目录。运行 /app 只读，C++ 产物须有执行位且挂载允许执行；/tmp 保持 noexec。每个镜像验证 nobody 用户、sleep 命令、ENTRYPOINT、编译器/解释器绝对路径与容器工作目录，避免继承镜像启动脚本改变命令。
- 用固定 UID/GID 和目录权限替代泛化 world-writable；工作区父目录必须阻止其他任务访问。限制编译产物大小及工作区总空间，输入源码限额不能替代产物限额。
- Linux CPU 限额用实际生效的 quota/period 或 NanoCPUs，并验证 cgroup；不把现有 CpuCount=1 当作已经验证的 Linux CPU 限额。内存与 swap 同额的含义按部署环境验证。[Docker 资源限制文档](https://docs.docker.com/engine/containers/resource_constraints/)
- 当前 sleep 主进程 + exec 架构下，需验证用户 exec 进程 OOM 时是否能正确识别，不能只依据主容器 OOMKilled 或退出码 137 推断 MLE。若无法可靠归因，改用用户程序作为容器主进程或可信监督器，验收前解决。
- 输出按字节有界收集，跨帧 UTF-8 使用增量解码；达到 stdout 上限立即终止任务，stderr 独立限额。退出码未知/inspect 失败必须给 SYSTEM_ERROR，不能按 stderr 为空猜测成功。
- Docker 连接、创建、启动、执行、清理均设置期限；编译失败、超时、进程中断、服务重启遗留容器均有清理策略。C++ 编译和 Python 语法检查也受限，不能只限制运行阶段。
- Java Gson 改为语言专属可信依赖准备，并验证 Spring Boot fat JAR 部署；Python/C++ 不再无条件复制 Gson。

## 6. 状态、版本与数据兼容

继续使用稳定语言值 java/python/cpp，不因运行时小版本更改数据库 language 字段。实施前检查真实 DDL 的长度与约束；当前枚举已支持这些值不代表真实数据库无需验证。

建议在提交记录的独立 execution metadata（JSON 列或等价关联表）保存 runtimeId、镜像 digest、实际工具链版本、Harness/SDK 版本、policyVersion、effectiveLimits。提交接收时固定目标版本，MQ 消费按该版本路由；滚动升级期间保留旧运行时直至旧任务排空。仅日志记录不能支撑历史追溯。复判是否使用旧版本必须显式选择，不能用当前配置冒充历史运行环境。

两套 `model.execution`（backend-model 与 sandbox）目前分开定义，新增字段与状态码同步更新并做 JSON 契约测试，防止单边升级丢字段。

| 情形 | 结果与队列行为 |
|---|---|
| 未支持/未开放的语言或模式 | 入队前拒绝；消费/沙箱防御性命中则终局 LANGUAGE_NOT_SUPPORTED，不重试 |
| 用户语法/编译错误 | CE，不重试；编译资源超限提供明确诊断 |
| 异常退出、信号终止、用户破坏协议 | RE，不重试；OOM/TLE/OLE 证据优先按对应状态处理 |
| 输出不匹配 | WA，不重试 |
| Docker 不可达、镜像缺失、可信 Runner/SDK 损坏 | SYSTEM_ERROR，现有有界重试/死信与告警；不伪装成用户 CE |
| 非法执行计划 | 入队前尽量拒绝；内部契约错误单独诊断，避免无意义重复执行 |

响应必须校验 overallStatus、逐例状态、caseIndex 唯一性/完整性及协议结构，不能默认未知状态为成功。可信 Runner 损坏与用户代码错误需由构建烟测及明确错误来源区分，不能仅凭“编译器非零”给所有情况标 CE。

## 7. 实施顺序与验收

### 阶段 A：公共契约与资源前置修复

完成模式门禁、配置校验、实际资源预算、语言布局、跨语言签名校验、元数据与协议契约测试；修复编译 tmpfs、退出码、输出与 OOM 识别问题。保留 Java 回归通过，新语言仍关闭。

主要改动：model 两侧 DTO、question 提交/题目校验、judge 结果处理与 DefaultJudgeStrategy、sandbox 工作区/执行引擎/配置校验。

### 阶段 B：ACM 三语言贯通

实现 PythonRuntimeAdapter、CppRuntimeAdapter 与 ACM 文件映射，构建固定镜像、预拉取、启动能力烟测。三种语言同一题得到一致结果。此阶段仅允许对 Python/C++ 开放 ACM，可作为内部试运行里程碑。

### 阶段 C：FUNCTION 三语言贯通

先抽象现有 Java Harness，再实现 Python/C++ Runner 和类型转换、节点 SDK、原地修改、模板与题目能力判定。所有现有 DSL 类型通过后开放 FUNCTION；只做标量函数不视为完成。

### 阶段 D：部署验收与启用

在目标 Linux + Docker 环境运行真实编译执行及完整“提交 → MQ → judge → sandbox → 结果落库”链路，完成容量测试和线上开关演练。原记录指出本地缺 Docker，单元测试通过不能替代此阶段。

最低验收矩阵：

| 类别 | 必测内容 |
|---|---|
| 正常链路 | 3 语言 × 2 模式；多例、空输入、Unicode、换行；Java 原有提交与 V1 回归 |
| 结果分类 | AC、WA、CE（Python 含语法错误）、RE、TLE、MLE、OLE；编译超时与编译内存超限 |
| FUNCTION 类型 | 全部白名单、整数边界与溢出、空数组/矩阵/链表/树、null、转义、浮点、全部 resultSource |
| 进程与资源 | 死循环、fork/线程爆炸、超大 stdout/stderr、段错误、OOM、残留子进程、编译产物膨胀 |
| 文件与命令 | 路径穿越、重复/保留文件名、错误扩展名、非法入口、环境变量污染、跨任务读取尝试 |
| 配置与故障 | 关闭语言、仅开 ACM、镜像不存在/版本错误、无适配器、重复注册、Docker 超时、错误鉴权、禁网 |
| 队列与升级 | 入队后禁用、消费重试、运行时切换、重复消息、旧版本排空、任务失败终局与容器清理 |
| 差分与性能 | 同一题的三语言正确/错误题解；时间倍率边界；资源与 HTTP/MQ 超时一致；并发容量 |

发布顺序：离线构建/扫描镜像并固定 digest → 部署支持新契约的 sandbox → judge → question → 内部烟测 → 开启 ACM → 验收 FUNCTION 后开启 → 前端能力列表联调。能力探针运行最小编译/执行样例，不仅检查镜像是否存在。

回滚先关闭入口的新语言提交，再按任务目标版本排空或显式终止在途任务，保留旧镜像和已发布 Harness。不要简单卸载 Adapter，让队列任务不断重试。回滚不删除历史 Python/C++ 提交；新增数据字段采用向后兼容迁移。

## 8. 横向对比与选择理由

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| 现有 V2 + Runtime Adapter + 多语言 Harness | 复用注册表、容器执行、MQ、比较器；改动可分阶段 | 必须补齐当前资源与协议缺口 | 本次采用 |
| 每种语言复制完整判题/沙箱服务 | 初期容易独立试验 | 门禁、资源、错误与修复逻辑易分叉 | 不采用 |
| 接入完整第三方判题平台 | 可获得成熟运行时管理能力 | 需重做 FUNCTION/ProblemSpec 适配、部署与协议验证 | 后续规模变化时再评估 |

选择理由：当前项目已经具备多语言适配骨架，主要缺口在执行计划、FUNCTION 生成器和运行时保障。沿用这些边界能够保留 Java 行为，并让下一种语言通过新增布局、Harness 和 Runtime Adapter 扩展。

仍需实施阶段提供的证据：目标服务器 CPU 架构与容量、镜像精确补丁版本/digest、可信 JSON 组件固定版本、Python 时间倍率校准、存量题目签名审计、真实 Linux Docker 验收及前端联调。这些是发布参数与验证结果，不应在设计阶段伪称已完成。
