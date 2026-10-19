# 多语言运行时构建与验收

实现对应 `record/introduction/MultiLanguage.md`。默认仍只开放 Java；不要把本机 Runner 测试当成生产验收。

## 构建固定镜像

基础镜像必须来自审核后的 Python 3.12 / GCC 14 镜像，指定目标架构、完整补丁版本和 `@sha256:` digest。下列变量必须由发布环境提供，不在仓库编造 digest。

```sh
docker build --build-arg BASE_IMAGE="$PYTHON_BASE_DIGEST" --build-arg PYTHON_VERSION="$PYTHON_VERSION" -t nemo-python:reviewed deploy/runtimes/python
docker build --build-arg BASE_IMAGE="$GCC_BASE_DIGEST" --build-arg GCC_VERSION="$GCC_VERSION" -t nemo-cpp:reviewed deploy/runtimes/cpp
```

在上述 C++ 镜像已经审核并固定 digest 后，可构建核心代码模式的分离式协议镜像：

```sh
docker build --build-arg BASE_IMAGE="$CPP_REVIEWED_DIGEST" -t nemo-cpp-split:reviewed deploy/runtimes/cpp-split
```

该镜像在构建时把可信的 JSON 协议实现编译为 `/opt/nemooj/libjson-lite.a`，每次提交仍独立编译用户代码并生成新容器。`ACM` 模式不链接该库。生产环境应扫描并推送镜像，以最终 digest 配置 `SANDBOX_CPP_IMAGE`；不能直接使用验收虚拟机的 `:split-20260929` 标签。

切换前排空未完成提交与 RabbitMQ 判题队列，先部署兼容新旧模式的 judge/sandbox JAR，再切换沙箱镜像并设置 `SANDBOX_CPP_SPLIT_PROTOCOL_ENABLED=true`，最后设置 `JUDGE_CPP_SPLIT_PROTOCOL_ENABLED=true` 并刷新判题服务。两个开关与镜像必须配套；需要回滚时先关闭 judge 开关，再把沙箱恢复到旧镜像并关闭沙箱开关。切换后验证 Java、Python、C++ 的 ACM/核心代码、链表/树结构、CE 诊断和资源统计。C++ 端到端对照及回归结果见 `record/performance/2026-09-29/CORE_OPTIMIZATION_IMPLEMENTATION.md`。

构建后扫描、推送并记录最终镜像 digest，用最终 digest 配置 `SANDBOX_PYTHON_IMAGE` / `SANDBOX_CPP_IMAGE`。生产启动检查启用语言的 digest；未启用语言允许不配置镜像。

C++ 内部依赖为 nlohmann/json 3.11.3，官方源码：
https://github.com/nlohmann/json/tree/v3.11.3 。单头文件和 MIT 许可证已随仓库保存，构建时验证 SHA256：
`9bea4c8066ef4a1c206b2be5a36302f8926f7fdc6087af5d20b417d0cf103ea6`。
评测时不下载依赖。Python/C++ 镜像的解释器/编译器固定在 `/usr/local/bin`，需存在 `/bin/cat`、`/bin/sleep`。

## 发布前置项

1. 先执行增量迁移 `deploy/migration/V6__submission_runtime.sql`，再部署沙箱、judge、question。旧提交的运行时快照保持 NULL，不能伪造历史版本。
2. Linux Docker 使用 cgroup v2，容器内须可读 `/sys/fs/cgroup/memory.events`。不能读取 OOM 计数时返回系统错误，不猜测成功或 MLE。此要求也适用于 Java 共用执行引擎，升级前做 Java 回归。
3. 沙箱服务进程拥有工作目录，并有设置目录 GID 65534 的权限（非 root 服务需加入该组）。用户容器固定 `65534:65534`，工作目录 0770，工作区父目录 0700。绑定路径必须在沙箱服务与 Docker 宿主机一致。
4. 为工作区挂载独立、有磁盘配额的文件系统。单文件 RLIMIT_FSIZE=64MiB、工作区轮询上限=128MiB/1024 文件、/tmp=64MiB；轮询检查不能代替文件系统硬配额。编译工作区轮询间隔 200ms。
5. 编译上限默认 Java/C++ 30s、Python 10s；运行倍率 1/3/1。`sandbox.language-policies.<language>` 可配置 compile-timeout-seconds、compile-memory-bytes、runtime-factor、process-limit、output-limit-bytes。配置必须通过边界检查。倍率最大 3，编译时间最大 60s。
6. 默认整次任务预算 `sandbox.limits.max-task-time-ms=600000`，包含编译和每例运行/容器操作余量；question 在入队前检查，sandbox 再检查。HTTP 等待按最坏预算扩展，判题租约继续依赖既有心跳续约。

节点约定：空链表/空树的返回值统一为 JSON `null`（兼容原 Java Runner）；空数组仍为 `[]`。节点输入同时接受 `null` 和 `[]`。

## 统一开关

三个服务使用同一发布配置：

```sh
JUDGE_ENABLED_LANGUAGES=java,python,cpp
JUDGE_PYTHON_MODES=ACM
JUDGE_CPP_MODES=ACM
```

先验收 ACM，再将对应 modes 改为 `ACM,FUNCTION` 并重新部署。Java 默认两种模式。新统一开关优先于旧 `JUDGE_LANGUAGES` / `SANDBOX_LANGUAGES`，旧变量只作为回退。

## 能力接口和版本

- 沙箱内部：`GET /api/v2/capabilities`，使用既有 `auth` 密钥；每 30 秒重新编译/执行最小程序验证工具链，单语言故障不开放该语言。
- judge 内部：`GET /api/judge/inner/capabilities`，使用既有内部鉴权，并结合 judge 开关。
- 公开：`GET /api/question/languages?questionId=<id>`，省略题号返回 ACM 能力。返回可用状态、原因、模板；不返回镜像地址。FUNCTION 模板支持全部当前 DSL，C++ 可变 vector 参数使用引用。
- question 新提交在入库前查询能力，依赖失败不会使用无限期缓存。提交 `runtimeMetadata` 保存运行时、镜像、工具链、SDK、策略和预期预算。ExecutionPlan 携带 runtimeId；沙箱拒绝执行与当前镜像/策略不同的任务。结果 JudgeInfo 包含实际预算和未缩放的测量时间。
- 当前部署约定为单个直接沙箱端点。不要在 `SANDBOX_BASE_URL` 后放置运行时不一致的随机负载均衡节点；异构节点的能力交集/精确路由尚未实现。滚动升级先关闭入口、排空旧任务，再换镜像。

## 验证命令

本机无 Docker 的测试：

```sh
# Python 3.12，C++ 编译器及 vendored header 的绝对目录
export NEMO_TEST_PYTHON=/path/to/python3
export NEMO_TEST_CPP=/path/to/g++
export NEMO_TEST_CPP_INCLUDE=/absolute/repo/deploy/runtimes/cpp
mvn -s build/maven-settings.xml -f OJ-backend-microservice/pom.xml -pl OJ-backend-judge-service,OJ-backend-question-service -am package
mvn -s build/maven-settings.xml -f nemo-sandbox/pom.xml test
```

没有设置 NEMO_TEST_* 时 native Runner 测试会明确 skip，普通 Java 单元测试仍执行。

隔离的目标 Linux + Docker 环境：配置鉴权、固定镜像并启用三语言后，运行

```sh
export SANDBOX_BASE_URL=http://sandbox:8090
# SANDBOX_SECRET 由发布环境注入，不打印
python3 deploy/runtimes/acceptance.py
```

该脚本验证三语言 ACM 正常输出、新语言 CE/RE/TLE/MLE/OLE、时间倍率。还必须在目标 GCC 14 上运行 native FUNCTION 测试，完成真实提交 → MQ → judge → sandbox → 落库链路、并发容量/进程爆炸/容器清理/镜像故障/禁用演练、旧 Java V1 回归及前端联调。没有这些证据，不能声称阶段 D 已完成。

回滚先关闭新语言入口，保留适配器、镜像和历史提交；已绑定旧 runtimeId 的任务需排空或显式终止，不能静默换版本执行。数据库字段采用加法迁移，不回删历史快照。

真实 FUNCTION Docker 契约矩阵（42 组，3 语言 × 14 类型）：

```sh
export NEMO_TEST_SANDBOX_URL=http://sandbox:8090
mvn -s build/maven-settings.xml -f OJ-backend-microservice/pom.xml -pl OJ-backend-judge-service -am -P integration -Dit.test=FunctionDockerIT -Dfailsafe.failIfNoSpecifiedTests=false verify
```

此集成测试也从现有 `SANDBOX_SECRET` 环境变量读取鉴权，不把答案放入 ExecutionPlan。它不替代完整 MQ/数据库链路验收。

补充验收脚本（仅针对隔离测试环境）：

- `fullchain_acceptance.py`：通过用户和题目 HTTP 接口注册、登录、发布并提交三语言 ACM/FUNCTION，等待异步结果，检查实际预算和运行时元数据。默认连接虚拟机 localhost:18102/18103。
- `hardening_acceptance.py`：鉴权、只读目录、UID/GID、禁网、CPU/内存/PID 限额、受限进程创建、Java V1、运行时版本拒绝、8 请求并发及恢复。
- `fault_acceptance.py`：在 `/home/nemo/nemooj-acceptance-20260918` 创建临时沙箱，验证禁用语言和缺失镜像，结束后移除临时容器。
- `acceptance-services.yml` / `acceptance-service-config.yml`：独立 MySQL/Redis/RabbitMQ 和 user/question/judge 测试栈。需准备 apps/*.jar、随机凭据 acceptance.env、数据库 V1–V6，并把沙箱以 sandbox 别名接入测试网络。该配置使用静态服务地址，不包含网关、Nacos 和前端验收。

真实测试记录见 `record/log/2026-09-19-02-00-multilanguage-container-validation.md`。这些单机功能和有限并发测试不代表生产容量上限或完整安全审计。
