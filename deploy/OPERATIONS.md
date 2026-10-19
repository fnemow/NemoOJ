# 双机轻量运维

腾讯云运行核心 OJ，阿里云运行 AI 服务。宿主机 Python 与 systemd 执行备份和监测，不增加常驻监控容器。

## 安装与权限

在核心主机以 root 执行 `deploy/ops/install.sh core 10.88.0.2`，AI 主机执行 `deploy/ops/install.sh ai 10.88.0.1`。
需要 Docker、GnuPG、SSH、Python 3；通用备份脚本兼容阿里云当前 Python 3.6，云端容量验收脚本仅在 Ubuntu 核心主机运行。

初始化会生成 `/etc/nemo-oj/backup_ssh`，其公钥交给对端 `nemo-backup` 用户：

- `authorized_keys` 使用 `from="对端WireGuardIP",restrict,command="/usr/bin/python3 /usr/local/lib/nemo-ops/receive.py --role 对端角色"`。
- 私钥与 `ops.json` 仅 root 可读；`backup_known_hosts` 固定对端主机公钥，禁止关闭主机密钥检查。
- `nemo-backup` 只能接收加密归档、恢复密钥与状态心跳，不能执行任意命令、转发端口或获得终端。
- 首份备份及恢复验证通过后，才启动 `nemo-backup.timer`、`nemo-monitor.timer`。

## 备份与恢复演练

`python3 /usr/local/lib/nemo-ops/backup.py --role core` / `--role ai`：

- MySQL 一致性 SQL 备份或 PostgreSQL 自定义格式备份，并打包相应部署配置。
- AES-256 加密，发布前解密核验归档；经 WireGuard SSH 传送到另一台主机，校验对端返回的密文 SHA-256。
- 本机 `/var/backups/nemo-oj/角色/` 保留 7 天，对端 `/var/lib/nemo-backup/角色/` 保留 14 天。
- 恢复密钥在 root 私有目录保存，并在对端独立文件保留一份恢复副本。它与归档都属于敏感运维资料，不得公开下载或提交 Git。
- 默认每天主机时间 03:20 加随机最多 10 分钟执行。查看 `systemctl list-timers nemo-backup.timer` 确认时区与下一次执行时间。

`python3 /usr/local/lib/nemo-ops/restore_verify.py --role core` / `--role ai`：

- 解密、校验数据库摘要，在与生产相同的数据库镜像中恢复。
- 演练容器无网络、无宿主机端口，限制 512 MiB 内存和 1 核，不覆盖真实库。
- 校验题库、提交、用户、迁移记录或 AI checkpoint 表，完成后删除演练容器与临时数据。

真正灾难恢复必须先停写、保存故障现场，再由运维选择备份和恢复目标。演练命令不会执行原地恢复。

## 监测与提醒

每分钟检查必需服务、宿主机内存、磁盘、超过 2 分钟的未完成判题、终止投递的 Outbox、备份时效以及 AI 每日预算。
AI 主机通过受限 SSH 发送心跳；核心主机超过 4 分钟未收到心跳会记录异常。
异常持续 2 分钟才提醒，同一异常去重，恢复后发送恢复通知。发送失败下次重试。
SMTP 沿用核心主机私有 `.env`，只发送运维状态，不发送用户代码、反馈正文、密码或模型密钥。

状态文件在 `/var/lib/nemo-ops/`，备份成功记录与恢复证据在 `/var/backups/nemo-oj/角色/`。
使用 `journalctl -u nemo-backup.service -u nemo-monitor.service` 查看结果，禁止直接输出部署 `.env`。
手动监测与定时监测共用非阻塞进程锁，避免重复告警；状态文件使用独立临时文件原子替换，防止并发写入竞争。
部署时务必检查核心主机 `.env` 的 SMTP 配置已完整填写，不能依赖示例文件中的空值；可通过 TLS 登录与 NOOP 检查连接，勿输出授权码。

## 容量验收

`cloud_acceptance.py` 是显式运行的维护工具，不会加入 systemd 定时任务。
通过回环 API 测试 Java / Python / C++ 两种模式、CE / RE / TLE，然后执行 5 分钟限速混合负载。
测试账号直接使用 BCrypt 写入数据库，密码仅在内存保存；不开放注册页面或接口。
仅在全部提交终态后软删除测试账号与提交，数据库触发器同步恢复统计。
同机发压会消耗主机资源，结果用于该配置的可用负载范围，不能作为生产最大并发结论。
## 2026-10-03 调度与故障恢复

4 GiB 验收层新增 `NEMO_CORE_JUDGE_CONCURRENCY`、`NEMO_CORE_SANDBOX_SLOTS`，默认均为 1。保持 `prefetch=1`；无待判题任务时才调整并发或回滚。

健康验证只在沙箱整体空闲时使用槽位，缓存读取不占槽位。优先级等待数通过 `nemo_sandbox_tasks_waiting{priority=FORMAL|TRY_RUN|HEALTH}` 查看；语言能力有明确验证有效期，忙碌不无限续期。

`remote_load.py` 从独立主机通过私有 API 隧道发压，`--duration 900 --interval 6` 用于持续负载，`--burst 8` 用于突发对比。证据保存测试账号 ID，断线后先核对这些账号的提交终态，再软删除；中断结果不得标为完整验收通过。

运维状态新增 PSI 和 swap 页面计数。历史 swap 占用不能单独说明当前内存不足，应看测量区间的增量、压力和 OOM。

AI 用户额度返还与平台预算结算分开：不确定是否已计费的模型失败保留平台预留，直到当日预算周期结束。告警包括这些预留；配置预算属于预估准入控制，实际费用还需核对提供方账单。结果回传失败先重传结果，恢复时复用已完成的 checkpoint。

`catalog_audit.py` 只生成结构巡检报告；边界用例缺失需要结合题面约束复核。`catalog_reference_audit.py` 对代表参考算法进行独立暴力验证，不能替代完整题库审核。两者都不会修改题库。

公开题库分页接口在两份 SSH 验收代理配置中单独开启 gzip，响应带 `Vary: Accept-Encoding`。认证、提交源码、AI 报告和 SSE 不加入这项压缩。修改绑定的 Nginx 配置文件应保留文件 inode，先执行 `nginx -t` 再重载；保留原配置以便回滚。

独立验收使用 SSH 长连接输出资源采样，最终清理最多重试 3 次；短暂探测失败保留在证据中，连续失败则停压。批量测试登录同样遵守真实 20 次 / 300 秒 IP 窗口，不通过清空 Redis 或修改限流配置规避。发压周期从测试账号完成登录后开始计算。

本轮实际云端保留 2 个消费者、2 个沙箱槽位，`prefetch=1`。第 3 题的 8 份突发全部完成时间由单槽位 27.938 秒缩至双槽位 14.734 秒；每分钟 10 份正式提交加 4 个查询线程持续 15 分钟全部通过。完整范围与少量后台换页观测见 `record/log/2026-10-03-judge-scheduling-capacity.md`；不能把该测试换算为全题库最大并发。

## 全局准入、模型账本与混合验收

`JUDGE_ADMISSION_MAX_PENDING=12`、`JUDGE_ADMISSION_MAX_OLDEST_SECONDS=120`、`JUDGE_ADMISSION_RETRY_AFTER_SECONDS=5` 在开发和部署使用同名变量。
未完成数来自数据库，覆盖尚未投递的 Outbox、排队和运行中的正式提交，包括私人题目；事务回滚不会泄漏名额。
准入拒绝指标为 `nemo_judge_admission_rejected{reason=capacity|queue_age|transaction_busy}`。提交 ID 只进日志。
HTTP 503 `JUDGE_BUSY` 不自动重试，用户稍后手动提交；既有滑动窗口、每用户待完成上限及幂等检查继续生效。

`AI_MODEL_MAX_CALLS_PER_TASK=4` 限制一次任务的提供方调用次数，包括任务重试和私人题目的修复调用。
每次调用记录 STARTED/KNOWN/UNKNOWN，未知费用不会因后来成功而清零；晚到的可靠回执可单独结算自己的调用。
按实际调用日期对逐次账本求和，旧任务没有逐次记录时使用原 `ai_usage`，避免重复计数。
估算价格来自私有 Agent 配置；对账仍以提供方账单为准，不应将 UNKNOWN 当作零费用。
回滚到不支持逐次账本的旧应用前，先停用 AI 新任务并排空已有任务；保留新账本，核对未决预留后再决定是否恢复旧应用的预算准入，不能让新旧计费实现带任务混跑。

判题服务每 5 秒检查健康缓存是否过期，沙箱验证仍只使用空闲槽位，忙碌期间不会无限延长有效期。
`remote_load.py --profile mixed --duration 1200 --interval 4` 覆盖三语言、两种模式、数组/动态规划/链表/二叉树及 CE/RE/TLE；保持 4 个查询线程。
`admission_acceptance.py` 单独验收满载准入与幂等重放。`fault_recovery_acceptance.py` 必须在压测排空后显式运行，会短暂中断判题服务、RabbitMQ 和 AI 私有代理，并在 finally 恢复服务。
故障期间允许租约重试；正常负载不应增加 attemptCount。演练账本保留真实模型成本，测试账号和提交仅在全部终态后软删除。

`catalog_quality_review.py 输出文件.json` 生成 20 道题的约束审核和最小合法用例。
在核心主机使用 `catalog_quality_publish.py 输出文件.json 报告.json`，通过管理员新版本草稿发布，保持自然题号和历史快照。
已创建的草稿可恢复发布；相同内容不再发布新版本。种子生成器也已纳入边界修正，**不可在现有云数据库重跑会清空提交的基础种子 SQL**。
结构巡检仍可能提示最小合法长度为 2 的题目，结合人工审核记录解释，不能为了消除告警加入违反题面约束的空输入。
