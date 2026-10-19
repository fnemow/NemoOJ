# 运维手册：备份、恢复、回滚与故障处置

配套 [DEPLOY.md](DEPLOY.md)。本文中的命令均需替换 `<DB_ROOT_PASSWORD>` 等占位符。

## 1. 故障域与 RPO/RTO

**必须如实说明：本方案是单机部署，不提供高可用。**

| 项 | 现状 | 说明 |
|---|---|---|
| 故障域 | 单台主机 | 主机宕机 = 服务完全中断。MySQL/Redis/RabbitMQ/Nacos 与应用同机 |
| RPO（可容忍的数据丢失） | **取决于备份周期，未实测** | 若每日全量备份，最坏丢失 24 小时提交；若开启 binlog，可缩短到分钟级。**未做恢复演练前不得对外承诺任何具体值** |
| RTO（恢复所需时间） | **未实测** | 需演练后才能给出。粗估：从备份恢复 MySQL + 重启服务 ≥ 30 分钟，但这是估计不是测量 |
| 已缓解的单点 | 判题进程 | 判题服务进程被杀不会丢任务（租约回收器接管，实测约 3 秒重新排队）；但**主机**宕机不在此列 |
| 已缓解的单点 | MQ 短暂不可用 | 发件箱保证提交不丢，MQ 恢复后自动补投（T05 实测） |

**结论：在完成备份恢复演练之前，对外只能声明"尽力而为的可用性"，不能宣称高可用。**

## 2. MySQL 备份

### 每日全量

```bash
#!/usr/bin/env bash
# /etc/cron.daily/nemo-oj-backup
set -euo pipefail
BACKUP_DIR=/var/backups/nemo-oj
RETENTION_DAYS=7
mkdir -p "$BACKUP_DIR"
STAMP=$(date +%F-%H%M)

docker compose exec -T mysql mysqldump -uroot -p'<DB_ROOT_PASSWORD>' \
  --single-transaction --routines --triggers --events \
  --set-gtid-purged=OFF nemo_oj | gzip > "$BACKUP_DIR/nemo_oj-$STAMP.sql.gz"

# 备份也要校验：只写不验的备份在需要时往往不可用
gzip -t "$BACKUP_DIR/nemo_oj-$STAMP.sql.gz"

# 清理超期备份
find "$BACKUP_DIR" -name 'nemo_oj-*.sql.gz' -mtime +$RETENTION_DAYS -delete
echo "备份完成: nemo_oj-$STAMP.sql.gz"
```

`--single-transaction` 保证 InnoDB 表的一致性快照，不锁表。

### 更小的 RPO：开启 binlog

若需要分钟级 RPO，在 `docker-compose.yml` 的 mysql 服务中加：

```
command:
  - --log-bin=/var/lib/mysql/mysql-bin
  - --binlog-expire-logs-seconds=604800   # 保留 7 天
```

并定期 `mysqlbinlog` 归档。**开启后需确认磁盘容量**，binlog 增长可能很快。

### 恢复演练（上线前必做）

```bash
# 1) 在隔离环境恢复，不要在生产上试
gunzip -c /var/backups/nemo-oj/nemo_oj-<stamp>.sql.gz | \
  docker compose exec -T mysql mysql -uroot -p'<口令>' nemo_oj

# 2) 核对：历史提交仍可查询
docker compose exec -T mysql mysql -uroot -p'<口令>' nemo_oj -e \
  "SELECT COUNT(*) AS submits FROM question_submit;
   SELECT status, COUNT(*) FROM outbox_event GROUP BY status;"

# 3) 记录实际耗时 —— 这个数字才是 RTO 的依据
```

## 3. 应用回滚

**原则：优先回退应用产物，数据库回滚单独评估，不自动执行破坏性 down SQL。**

### 回滚前检查

```bash
# 1) 确认没有正在进行的判题（回滚会让它们失去执行者）
docker compose exec -T mysql mysql -uroot -p'<口令>' nemo_oj -e \
  "SELECT COUNT(*) AS judging FROM question_submit WHERE status = 1;"

# 2) 确认没有待投递事件（旧版本可能不会投递它们）
docker compose exec -T mysql mysql -uroot -p'<口令>' nemo_oj -e \
  "SELECT status, COUNT(*) FROM outbox_event GROUP BY status;"
```

若存在 `status = 1`（判题中）的提交：等待其完成，或停止接单后由租约回收器
在租约过期（默认 120 秒）后重新排队。**不要直接回滚应用**，否则这些提交会
因缺少执行者而永久停在判题中。

若存在 `status IN (0,1)` 的待投递事件：先确认新版本的投递器已把它们发出去
（`status = 2`），再回滚。否则回滚后这些事件不会被任何版本投递。

### 回滚步骤

```bash
# 1) 停止接单：把网关下线，业务服务保留运行以便排空
docker compose stop proxy

# 2) 等待在途请求与判题完成（观察上面的 status=1 计数归零）

# 3) 回退到上一版本镜像
vi deploy/.env      # 把 NEMO_*_IMAGE 改回上一版本标签
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d \
  user-service question-service judge-service gateway sandbox

# 4) 恢复入口
docker compose start proxy

# 5) 验证
curl -s https://oj.example.com/api/question/list/page/vo -X POST \
  -H 'Content-Type: application/json' -d '{"current":1,"pageSize":1}'
```

### 数据库回滚

**不建议自动回滚数据库。** 迁移只提供向上脚本，没有 down SQL。
原因是数据变更（如给提交补上版本快照）无法通过语句逆向还原成"原本没有"。

需要回退结构时，逐条评估：

| 迁移 | 可否安全回退 | 说明 |
|---|---|---|
| V2 账号唯一约束 | 可（`DROP INDEX`） | 但会重新允许重复账号 |
| V3 outbox_event | 可（`DROP TABLE`） | **会丢失待投递事件**，先确认无 `status IN (0,1)` |
| V4 判题租约字段 | 可（`DROP COLUMN`） | 旧版本应用不读这些列 |
| V5 版本快照 | **不可** | 版本行是历史提交的判题依据，删除后无法还原 |

**验证旧版本能否读取新增数据**：删除列/表前，先用旧版本镜像启动服务并确认
启动成功、历史提交可查询。新增列对旧版本是无害的（旧版本不读它们）。

## 4. Redis 与 Session

Redis 存两类数据，**丢失影响不同**：

| 数据 | 丢失影响 | 处置 |
|---|---|---|
| Session（`nemo-oj:session:*`） | **所有用户被强制登出**，需重新登录。已提交的任务不受影响 | 无数据损失，接受即可 |
| 限流计数（`login:*`/`submit:*`） | 配额重置，短时间内限流失效 | 影响有限；多实例下 Redis 故障会触发本地降级（配额收紧到 1/4） |

Redis 已开启 `appendonly yes`，重启后数据可恢复，但**不保证不丢最后一次写入**。

**Redis 长时间不可用的影响**：
- 用户服务：无法读写 Session → 用户无法登录（就绪判定会转为 DOWN）
- 题目服务：Session 读取失败 → 未登录；限流降级为本地模式
- 判题服务：不依赖 Redis Session，判题可继续

## 5. MQ 恢复后的对账

RabbitMQ 恢复后按以下顺序处理：

### 5.1 确认 outbox 是否自动补投

发件箱投递器会自动重试，**正常情况下无需人工干预**：

```bash
# 查看积压与最老等待（也可通过 Prometheus 指标 nemo_outbox_* 观察）
docker compose exec -T mysql mysql -uroot -p'<口令>' nemo_oj -e \
  "SELECT status, COUNT(*) cnt, MIN(createTime) oldest FROM outbox_event GROUP BY status;"
```

- `status = 0`：待投递，投递器正在重试（退避最长 10 分钟）
- `status = 2`：已送达 broker
- `status = 3`：**重试耗尽，需人工处理**（见 5.3）

### 5.2 确认消息是否真的进了队列

```bash
# 管理台未对外暴露，通过隧道访问
ssh -L 15672:127.0.0.1:15672 user@host
# 然后浏览器打开 http://127.0.0.1:15672，查看 judge.queue 的 messages_ready
```

或用 CLI：

```bash
docker compose exec rabbitmq rabbitmqctl list_queues name messages_ready messages_unacknowledged
```

### 5.3 处理永久失败的事件

```sql
-- 查看需处理的事件
SELECT id, eventId, bizId, retryCount, lastError, createTime
FROM outbox_event WHERE status = 3 ORDER BY createTime;

-- 确认原因（常见：routing key 配错、队列被删、broker 长时间不可达）后重投
UPDATE outbox_event
SET status = 0, retryCount = 0, nextAttemptAt = NOW(), lastError = NULL
WHERE id = <id>;
```

重投前先确认对应提交仍是"待判题"；若已由其它途径完成，重投不会造成错误
（消费者幂等），只是无意义。

### 5.4 解释"卡住的"提交

```sql
-- 按归属分类超时未完成的提交
SELECT
  SUM(s.status = 0 AND e.status = 0) AS 待投递,
  SUM(s.status = 0 AND e.status = 1) AS 投递中,
  SUM(s.status = 0 AND e.status = 2) AS 已投递未消费,
  SUM(s.status = 0 AND e.status = 3) AS 投递永久失败,
  SUM(s.status = 0 AND e.id IS NULL) AS 无事件_数据异常,
  SUM(s.status = 1) AS 判题中
FROM question_submit s
LEFT JOIN outbox_event e ON e.bizId = s.id AND e.eventType = 'JUDGE_SUBMIT'
WHERE s.isDelete = 0 AND s.status IN (0, 1)
  AND s.createTime < DATE_SUB(NOW(), INTERVAL 300 SECOND);
```

每一条未完成的提交都会落到上面某一类，不会出现"说不清去哪了"：

| 分类 | 含义 | 处置 |
|---|---|---|
| 待投递 / 投递中 | MQ 或投递器有问题 | 检查 MQ 连通性与投递器日志 |
| 已投递未消费 | 消息进了队列但没人处理 | 检查判题服务是否在运行、队列是否有消费者 |
| 投递永久失败 | 重试耗尽 | 按 5.3 人工重投 |
| 无事件 | 数据异常（可能是旧版本遗留） | 人工核对，必要时补建事件 |
| 判题中 | 正在执行或执行者已失联 | 租约过期后由回收器自动接管（默认 150 秒内） |

题目服务每分钟会自动执行同样的对账并输出告警日志，可用
`grep "对账:" ` 检索。

## 6. 常见故障处置

| 现象 | 可能原因 | 处置 |
|---|---|---|
| 提交一直停在"待判题" | MQ 不可达 / 投递器异常 | 按第 5 节对账；查 `nemo_outbox_pending_events` 与 `oldest_wait_seconds` |
| 提交停在"判题中"很久 | 判题进程被杀 | 无需干预，租约过期后回收器自动重排队（默认 150 秒内）；若持续发生查判题服务是否频繁重启 |
| 沙箱返回系统错误 | Docker 不可用 / 镜像缺失 | 查沙箱启动校验与 `docker images`；沙箱在 Docker 不可用时**拒绝启动**，因此若它在运行说明 Docker 曾可用 |
| 新提交被拒"提交过于频繁" | 触发限流 | 正常保护。阈值见 `.env`；被限流的请求不产生记录 |
| 新提交被拒"未完成提交过多" | 未完成配额用尽 | 等待判题完成；若长期不释放，检查判题是否正常（配额在终态释放） |
| 服务反复重启 | 存活探针失败 | 存活判定不含外部依赖，失败说明进程本身有问题，查应用日志与 OOM |
| 服务就绪但请求失败 | 依赖不可用 | 就绪判定应已转为 DOWN；若仍 UP，说明该依赖未纳入就绪判定，需补充 |

## 7. 数据保留

| 数据 | 建议保留 | 说明 |
|---|---|---|
| question_submit | 长期 | 用户历史记录 |
| outbox_event（status=2） | 90 天 | 已投递的投递凭证，过期可清理 |
| outbox_event（status=3） | **长期** | 需人工处理，清理前必须确认已处置 |
| question_version | **永久** | 历史提交的判题依据，删除会导致无法复现 |
| 日志 | 7 天 / 2GB | 由 logback 轮转策略控制 |
| MySQL 备份 | 7 天（可调） | 见第 2 节 |

清理已投递的 outbox 记录：

```sql
DELETE FROM outbox_event
WHERE status = 2 AND updateTime < DATE_SUB(NOW(), INTERVAL 90 DAY) LIMIT 1000;
-- 分批执行，避免长事务
```
