# 部署手册

面向 Linux 单机/小规模部署。备份、回滚与故障处置见 [RUNBOOK.md](RUNBOOK.md)。

腾讯云运行核心 OJ、阿里云运行 Agent 的配置与操作见 [TWO_HOST_DEPLOY.md](TWO_HOST_DEPLOY.md)。

> **状态说明**：本手册与模板**未在真实 Linux 环境验证过**——
> 开发机为 Windows 且未安装 Docker。首次部署请严格按顺序执行并逐段核对，
> 不要一次性 `up -d` 全部服务。未经实测的部分在文中标注为"待验证"。

## 0. 前置条件

| 项 | 要求 |
|---|---|
| 操作系统 | Linux（x86_64），Docker 24+ 与 Docker Compose v2 |
| JDK | 仅构建时需要 **17**；运行时由镜像自带 |
| 磁盘 | 建议 ≥ 50GB（判题镜像与沙箱工作目录占用较大） |
| 内存 | 建议 ≥ 8GB（MySQL + Redis + RabbitMQ + Nacos + 4 个应用 + 沙箱容器） |
| 域名与证书 | 已解析到本机的域名，以及对应的 TLS 证书 |

## 1. 准备目录与配置

```bash
sudo mkdir -p /var/lib/nemo-oj/sandbox
# 沙箱工作目录：必须位于**宿主机**路径上，且与沙箱容器内看到的路径一致
sudo chown 65534:65534 /var/lib/nemo-oj/sandbox   # nobody，与容器内非 root 用户一致
sudo mkdir -p /srv/nemo-oj && cd /srv/nemo-oj
# 将仓库中的 deploy/ 内容放到这里
cp deploy/.env.example deploy/.env
vi deploy/.env      # 逐项填写，特别是所有 REPLACE_ME
```

**必须替换的项**（未替换时应用会启动失败，这是刻意的）：

- `DB_ROOT_PASSWORD` / `DB_PASSWORD` / `DB_USERNAME`
- `REDIS_PASSWORD` / `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD`
- `INTERNAL_AUTH_SECRET`、`SANDBOX_SECRET`（各服务必须一致，长度 ≥ 16）
- `NACOS_AUTH_*`（Nacos 未配置独立鉴权密钥时会拒绝启动）
- `SANDBOX_JAVA_IMAGE`（必须带 digest）
- `CORS_ALLOWED_ORIGINS`（真实域名，不接受通配符）
- `PUBLIC_DOMAIN`（同时修改 `nginx/oj.conf` 中的 `server_name`）

生成随机密钥：`openssl rand -hex 24`

## 2. 沙箱路径一致性（容器化沙箱的关键点）

沙箱是通过 Docker daemon 创建**兄弟容器**来执行代码的，这些容器由宿主机 daemon 创建，
因此绑定挂载的路径是**宿主机视角**的路径。

若沙箱自身也容器化（本编排即如此），必须满足：

```
沙箱容器内的 /var/lib/nemo-oj/sandbox  ===  宿主机的 /var/lib/nemo-oj/sandbox
```

编排中通过同名挂载保证这一点。**若两者不一致，容器会挂载到一个空目录，
表现为"用户代码找不到文件"或"编译报错但看不出原因"**——这正是 T08 记录中
提到的"不能在应用容器内创建目录后假定宿主机可见"。

验证方法（部署后）：

```bash
# 在沙箱容器内创建文件
docker compose exec sandbox touch /var/lib/nemo-oj/sandbox/path-check
# 在宿主机上应能看到同一个文件
ls -l /var/lib/nemo-oj/sandbox/path-check
```

## 3. 数据库初始化与迁移

**发布顺序要求：先应用数据库增量，再切换应用。**

新库与存量库不同：

```bash
# ---- 新库 ----
docker compose up -d mysql
# 等 mysql 健康后，手动执行迁移（首次启动的自动初始化只适用于空库）
MYSQL_PWD='<DB_ROOT_PASSWORD>' docker compose exec -T mysql \
  mysql -uroot -e "CREATE DATABASE IF NOT EXISTS nemo_oj \
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
for f in deploy/migration/V*.sql; do
  echo "执行 $f"
  MYSQL_PWD='<DB_ROOT_PASSWORD>' docker compose exec -T mysql mysql -uroot nemo_oj < "$f" || exit 1
done
```

```bash
# ---- 存量库 ----
# 1) 先看状态
MYSQL_PWD='<口令>' deploy/migration/apply_migrations.sh \
  --host 127.0.0.1 --user root --database nemo_oj --list
# 2) 基线：把已存在的结构标记为已应用，再执行增量
MYSQL_PWD='<口令>' deploy/migration/apply_migrations.sh \
  --host 127.0.0.1 --user root --database nemo_oj --baseline-through V1
```

**V5 之后必须补发布存量题目**，否则存量已发布题目无法被提交：

```bash
mysql -uroot nemo_oj < deploy/migration/checks/backfill_question_versions.sql
```

先执行该脚本的「检测」段确认待补数量，再执行「补发布」段，最后用「复核」段确认无遗漏。
**执行前务必备份。**

## 4. 启动顺序

依赖未就绪时应用会快速失败退出（`restart: unless-stopped` 会重试），
但仍建议按下列顺序逐段启动，便于定位问题：

```bash
cd /srv/nemo-oj
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d mysql redis rabbitmq nacos
docker compose --env-file deploy/.env -f deploy/docker-compose.yml ps        # 等全部 healthy

docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d \
  user-service question-service judge-service sandbox gateway
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up -d proxy
```

### 就绪判定

```bash
# 管理端口不对外，在宿主机上直接访问
for p in 9102 9103 9104 9190 9101; do
  echo -n "port $p: "
  curl -s "http://127.0.0.1:$p/actuator/health/readiness" || echo "无响应"
  echo
done
```

存活与就绪语义不同，**不要用存活探针判断服务能否处理请求**：

- `liveness` 失败 → 容器需要重启（进程有问题）
- `readiness` 失败 → 实例不应接单（依赖不可用），会自动恢复

## 5. 管理员账号

**系统不预置任何管理员账号，也不提供默认密码。**

第一个管理员需手工建立：

```bash
# 1) 通过前端/接口正常注册一个账号（或直接插入 user 表，密码用 BCrypt 哈希）
# 2) 提升为管理员
mysql -uroot nemo_oj -e "UPDATE \`user\` SET userRole='admin' WHERE userAccount='你的账号';"
# 3) 验证：登录后调用任意 @AuthCheck(ADMIN_ROLE) 接口应成功
```

> 直接改库会绕过注册流程，因此**必须同时确认该账号的密码是 BCrypt 哈希**
> （`$2a$` 开头），不能是明文。

## 6. 部署后自检

按 T04/T11 的边界要求逐项核对：

```bash
# 1) 只有 80/443 对外开放
sudo ss -lntp | grep -E ':(80|443)\b'
# 期望：仅 nginx；不应出现 3306 / 6379 / 5672 / 8848 / 8101-8104 / 8090

# 2) 内部接口不可从公网到达
curl -s -o /dev/null -w "%{http_code}\n" https://oj.example.com/api/user/inner/get/id?userId=1
# 期望 403

# 3) 直连业务服务内部接口被拒（在宿主机上执行）
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8102/api/user/inner/get/id?userId=1
# 期望 403（无凭据）

# 4) 管理端点不在业务端口上
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:8102/actuator/health
# 期望 404

# 5) 沙箱路径一致性（见第 2 节）
```

## 7. 反向代理与 TLS

`nginx/oj.conf` 中的 `server_name` 与证书路径需按实际修改。
证书可用 certbot 签发后放入 `deploy/nginx/certs/`（`fullchain.pem` + `privkey.pem`）。

注意 `nginx.conf` 中 `proxy_set_header X-Forwarded-For $remote_addr` 是**覆盖**而非追加：
追加会把客户端伪造的段位保留在内，应用取第一段时就拿到了伪造 IP，从而绕过 IP 限流。

因此 `.env` 中 `RATELIMIT_TRUST_XFF=true` 只在**请求确实只经过本代理**时成立。
若前面还有 CDN 或云负载均衡，需要改为信任其注入的值，不能直接沿用本配置。

## 8. 已知限制

| 项 | 说明 |
|---|---|
| 单机部署 | 无高可用。本机故障即服务中断，见 RUNBOOK.md 的故障域说明 |
| 未实测 | 本手册与编排未在真实 Linux + Docker 上跑通，首次部署需逐段核对 |
| Nacos 单机模式 | 未配置集群；Nacos 不可用时已注册实例会在缓存过期后失效 |
| 沙箱与 daemon 同机 | 沙箱持有 docker.sock，等价于宿主机 root。该机应作为专用判题机，不放其它业务 |
