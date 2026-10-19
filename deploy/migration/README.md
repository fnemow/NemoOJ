# 数据库迁移

本目录是 NemoOJ **唯一**的数据库结构变更方式。不引入 Flyway / Liquibase，也不与其并用。

## 为什么不用自动迁移框架

发布顺序要求"**先应用数据库增量、后切换应用**"（见 T11）。迁移应当是运维显式执行、
可审计、可在应用切换前单独验证的动作，而不是随应用启动自动运行——
后者会让"应用启动失败"和"数据库被改了一半"两件事耦合在一起。

## 文件约定

```
deploy/migration/
  V1__baseline_schema.sql            基线：完整表结构（仅用于新库）
  V2__user_account_unique.sql        账号唯一约束
  V8__submission_execution_mode.sql  保存每次提交选择的 ACM/FUNCTION 模式
  V9__public_problem_number.sql      稳定的公开自然数题号
  V10__admin_lifecycle_feedback.sql  题目生命周期与用户反馈
  V11__ai_membership_tasks.sql       会员权益、AI 任务/额度、分析结果与发件箱
  V12__ai_learning_private_problems.sql 学习计划、私人题与公共题维护建议
  V13__ai_task_payload_and_plan_history.sql 任务载荷与学习计划历史版本
  V14__ai_notification_delivery_lease.sql  邮件发件箱租约与崩溃恢复
  V15__ai_maintenance_review.sql            AI 维护建议审核草稿引用
  V<n>__<描述>.sql                   后续增量
  checks/                            只读检查脚本，可随时在生产库执行
  apply_migrations.sh                执行器
```

- 文件名格式固定为 `V<序号>__<描述>.sql`，序号决定执行顺序，不要求连续。
- **已应用的迁移文件不可再修改**。执行器会记录每个文件的 SHA-256，
  内容变化会直接拒绝执行并报错。需要调整时新增一个版本号。
- 脚本不使用 `DELIMITER` 与存储过程，保证 `mysql` CLI 与 JDBC 都能执行。
- V11 及之后的迁移不自动执行；上线前先备份，再使用本目录的迁移执行器，并完成 AI 迁移检查。
- AI 回滚只关闭功能开关和回退应用，不删除 AI 业务表。开始写入私人提交后，必须保留支持
  `question_submit.problemSource` 的应用版本；直接回退到不识别该字段的旧版本不受支持。

## 执行方式

口令建议通过 `MYSQL_PWD` 环境变量提供，避免进入命令行历史与进程列表：

```bash
export MYSQL_PWD='<数据库口令>'
```

### 新库

```bash
./apply_migrations.sh --host 127.0.0.1 --user root --database nemo_oj
```

按序执行全部迁移。

### 存量库

存量库已经有表结构，不需要执行 V1。先把它标记为已应用，再执行后续增量：

```bash
# 1) 先看状态
./apply_migrations.sh --host 127.0.0.1 --user root --database nemo_oj --list

# 2) 基线：把 V1 标记为已应用（不执行 SQL），再跑后续迁移
./apply_migrations.sh --host 127.0.0.1 --user root --database nemo_oj --baseline-through V1
```

> 执行前务必备份。`--baseline-through` 只写版本记录表，不校验存量库结构是否真的与 V1 一致；
> 这一步的正确性由人工确认。

### 只预览不执行

```bash
./apply_migrations.sh ... --dry-run
```

### 退出码

| 码 | 含义 | 处理 |
|---|---|---|
| 0 | 成功 | — |
| 1 | 参数或环境错误 | 按提示修正 |
| 2 | 存在未处理的数据冲突 | 见下方「重复账号处理」，**数据库未被修改** |
| 3 | 迁移执行失败 | 数据库停留在该迁移执行前，修复后重试 |

## V2：账号唯一约束

### 背景

原实现依赖 `synchronized (userAccount.intern())` 保证账号不重复。这个锁**只在单实例内有效**：
两个实例并发注册同一账号时，双方都通过"先查后插"的检查，各写一条记录。
此外 `String.intern()` 的常量池会随注册量无界增长。

V2 把唯一性交给数据库唯一索引 `uk_user_userAccount`。

### 三条行为约定

1. **软删除后账号仍被占用。**
   唯一索引覆盖全部记录，包含 `isDelete = 1`。账号一经注册即永久占用，
   不能重新注册。这样可以避免账号被隐式重用后，历史提交记录的归属产生歧义。
   应用层的唯一性预检查用 `UserMapper.countByAccountIncludingDeleted`，
   口径与索引一致（不走逻辑删除过滤）。

2. **大小写不敏感。**
   比较沿用列自身的排序规则 `utf8mb4_unicode_ci`，实测 `'Alice' = 'alice'` 为真。
   这与应用层原有的查询行为一致，不是本次引入的新规则。

3. **忽略尾部空格。**
   同一排序规则下的实测结果：`'abc' = 'abc '` 为真。前导空格不作忽略。

### 执行前必须检查

```bash
mysql -u root -p nemo_oj < checks/duplicate_accounts_report.sql
```

最后一个结果集 `duplicate_account_count` 必须为 `0`。

执行器在应用 V2 前会**自动做这个检查**：只要存在重复账号就退出码 2，
打印完整冲突清单，且不对数据库做任何修改。即使绕过执行器直接执行 SQL，
MySQL 也会因重复数据拒绝建立唯一索引（错误 1062），不会留下半成品状态。

## 重复账号处理

出现冲突时，执行器会输出每个冲突账号的记录数、有效/软删除数量、涉及 id 与创建时间，
以及逐条明细（含该用户的题目数与提交数）。**本工具不会自动删除或合并账号**，
需要人工按业务判断处理。参考步骤：

1. **确认归属**：结合提交数、题目数、创建时间判断哪个是要保留的主账号。
2. **保留主账号，其余改名归档**，不要删除——历史提交记录仍引用原 id：
   ```sql
   UPDATE `user` SET userAccount = CONCAT(userAccount, '_dup_', id) WHERE id = <次要记录id>;
   ```
   改名后该记录仍可用于登录（用户需用新账号名），历史数据归属不变。
3. **若确认是废弃账号**，可在备份后软删除（`isDelete = 1`）——注意软删除**不能**解除
   账号占用，因此仍需先改名才能让主账号名通过唯一约束。
4. 处理完成后重跑执行器。

处理方案需要写进变更记录，不要在生产库上直接试。

## V3：发件箱（T05）

`V3__outbox_event.sql` 建立 `outbox_event` 表，支撑"提交落库与消息投递同事务"。

> 发布前修正记录：V3 首次编写时只用 `eventId`（随机串）标识事件，对账时试图用
> `CAST(question_submit.id AS CHAR) = eventId` 关联——既逻辑错误（随机串不可能等于提交 id），
> 又会在实测中报 `Illegal mix of collations`。现增加数值列 `bizId` 并据此关联。
> 该修正发生在本机测试库（已重建），未进入任何共享环境。**此后 V3 不可再改动。**

### 永久失败事件的人工处理

重试耗尽的事件会转入 `status = 3` 并**保留记录**（不会被清理）。
查看与重投：

```sql
-- 查看待处理项
SELECT id, eventId, bizId, retryCount, lastError, createTime
FROM outbox_event WHERE status = 3 ORDER BY createTime;

-- 人工确认原因后重投：重置为待投递并清零重试次数
UPDATE outbox_event
SET status = 0, retryCount = 0, nextAttemptAt = NOW(), lastError = NULL
WHERE id = <id>;
```

重投前应确认对应的 `question_submit` 是否仍处于待判题状态：
若该提交已被其它途径判定完成，重投不会造成错误（消费者幂等），只是无意义。

## V5：题目版本快照（T07）

`V5__question_version.sql` 新增 `question_version` 表与 `question_submit.versionReproducible`。

**上线顺序要求**：执行 V5 → 部署应用 → **对存量已发布题目补发布**。
补发布之前，存量已发布题目会因"没有版本号"而**无法被提交**（返回"题目数据异常"）。

补发布脚本：`checks/backfill_question_versions.sql`（含检测、补发布、复核三段）。

> 该脚本记录的是**当前**题目内容，不是历史某一时刻的内容。
> V5 之前已存在的历史提交，其真实判题依据已不可还原，
> 它们的 `versionReproducible` 保持 0——**不要**用这个脚本把它们"修成"可复现，
> 那是伪造历史快照。
>
> 脚本中 `harnessVersion` 统一置 1：该值嵌在 `problemSpec` 的 JSON 内，
> 纯 SQL 无法可靠提取。若需要精确值，应对题目走一次应用侧发布接口。

## 后续迁移

其余结构性变更由对应任务提供独立的 `V<n>__*.sql`，不并入本目录已有的文件。

## V10：题目生命周期与用户反馈

`V10__admin_lifecycle_feedback.sql` 为每个自然题号增加当前公开版本指针、
`DRAFT/ONLINE/OFFLINE` 状态、乐观锁版本和下线审计字段，并建立反馈工单表。
迁移会为存量题号选择最高的已发布题目版本作为当前版本；不存在已发布版本的题号保持草稿状态。

应用顺序：先执行 V10，再部署 question service 和前端。升级后可用以下只读查询核对：

```sql
SELECT problemNo, currentQuestionId, status, lifecycleVersion
FROM question_public_number ORDER BY problemNo;

SELECT COUNT(*) AS invalid_online_rows
FROM question_public_number
WHERE status = 'ONLINE' AND currentQuestionId IS NULL;
```

第二个查询必须返回 `0`。题目下线不会删除题目版本、版本快照或历史提交。
| V14 | AI notification delivery lease for retry-safe SMTP dispatch |
| V15 | Tracks administrator-approved editorial suggestion drafts |

## V18：一次性邀请注册

新增 `registration_invitation`，仅保存邀请码 SHA-256 摘要、有效期和使用/撤销审计。
先执行 V18，再部署用户服务和前端。旧账号保持不变，新注册必须使用管理员生成的有效邀请码。
管理员初始化脚本也要求 V18；它在主机内生成一个 15 分钟有效的一次性邀请，不提供免邀请码 HTTP 入口。
操作与回退说明见 [INVITED_REGISTRATION.md](../INVITED_REGISTRATION.md)。

## V19 / V20：全局判题准入与逐次模型调用记账

V19 新增单行事务锁和未完成提交查询索引；所有正式提交生产者必须在插入提交与 Outbox 的同一事务内校验。
默认最多 12 份未完成提交，最早一份等待超过 120 秒则暂停接收；拒绝返回 HTTP 503、`JUDGE_BUSY` 和 `Retry-After: 5`。
幂等重放已存在的请求仍返回原提交，不占新的名额。试运行仍使用独立的沙箱并发控制。

V20 新增 `ai_model_call`；Agent 每次请求提供方前先预留预算，收到用量后记录 token 和估算费用。
没有可靠用量回执的调用保持 STARTED/UNKNOWN 预留，重试成功只结算本次调用，保留之前不确定调用的预算。
账本不保存提示词或用户代码。按每次调用开始的上海日期计算平台预算，用户每日额度仍按任务结算一次。

升级顺序：停写并确认队列排空，备份，执行 V19/V20，更新问题服务和 AI 回调代理白名单，最后更新 Agent。
代理白名单和 CSRF 例外仅包含签名校验的调用 start/finish 路径，控制器仍强制校验 HMAC。
若回滚应用，保留这两张表、锁行和费用账本；不要删除真实调用或已发布题目版本。
