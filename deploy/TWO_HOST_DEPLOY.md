# 腾讯云 OJ + 阿里云 AI 双机部署

这套配置在腾讯云运行核心 OJ、MySQL、Redis、RabbitMQ 和题目服务；在阿里云运行 Nemo Agent 与它独用的 PostgreSQL。两机通过 WireGuard 互通。正式发布时公网用户只访问腾讯云的 80/443；首次验收阶段仅通过 SSH 隧道访问前端。Agent 经隧道访问 RabbitMQ 5672 和题目服务的四个签名回调接口。MySQL、Redis、PostgreSQL、Docker socket 和管理端口均不跨云开放。

本仓库提供 [`compose.core-cloud.yml`](compose.core-cloud.yml)、[`compose.agent-cloud.yml`](compose.agent-cloud.yml)、4 GB 验收叠加层 [`compose.core-4g-preview.yml`](compose.core-4g-preview.yml) 和 [`haproxy/ai-link.cfg`](haproxy/ai-link.cfg)。当前首次双机验收使用腾讯云 Ubuntu 24.04（4 核 4 GB）和阿里云 Alibaba Cloud Linux 3（2 核 2 GB）。正式公网发布仍需域名、TLS 与公开入口验收；沙箱工作目录和数据库迁移参见 [`DEPLOY.md`](DEPLOY.md)。

## 公网入口（2026-10-08）

公网 HTTPS 已启用，访问 `https://fw-nemo.cn/problems`。腾讯云增加 TCP 80/443 入站规则，HTTP 跳转 HTTPS；原 SSH 预览仍仅绑定回环地址。新增 `compose.core-public.yml` 补齐前端和 ACME 校验目录挂载，公网 Nginx 限制为 64 MiB；独立 `nemo-public.service` 依赖核心服务、跟随其重启并启用开机启动。

Certbot 5.8.0 使用 Webroot 自动续期，续期成功后校验证书、复制到代理挂载目录并热加载 Nginx；测试续期与 deploy hook 已通过。网关允许 `https://fw-nemo.cn` 和原 SSH 预览来源。实际 HTTPS、166 题分页、题目详情、静态资产、来源限制和内部接口阻断验收通过；公开题库列表沿用预览入口的 gzip 优化。

当前仍为题库浏览版，登录、注册和新提交未开放。启用 HTTPS 不等同于恢复用户功能；具体启用和停用公网步骤参见 [公网部署指南](PUBLIC_DEPLOY.md)。下方 2026-09-29 内容是首次 SSH 验收的历史记录。

## 首次双机验收（2026-09-29）

- 腾讯云使用三个 Compose 文件叠加运行：`docker-compose.yml`、`compose.core-cloud.yml`、`compose.core-4g-preview.yml`。预览 Nginx 只绑定 `127.0.0.1:18080`，未启用公网 `proxy`；使用 `ssh -N -L 127.0.0.1:18080:127.0.0.1:18080 ubuntu@<腾讯云公网 IP>` 后访问 `http://127.0.0.1:18080/problems`。
- 4 GB 叠加层给各服务设置内存上限，判题消费者和沙箱槽位暂各为 1。当前运行包没有 Nacos 注册客户端，故采用 `SPRING_APPLICATION_JSON` 静态服务发现；Nacos 放入 `legacy-nacos` profile，不作为此套部署的运行依赖。以后增加服务实例时，必须重新设计服务发现和路由。
- 沙箱服务的 `working_dir` 必须是宿主机和容器同路径的 `/var/lib/nemo-oj/sandbox`。否则源码留在容器内 `/app/tmpCode`，子容器的 `/app` 挂载为空，三种语言能力检查都会失败。`docker-compose.yml` 已固定这个路径。
- 全新数据库已应用 V1～V18 迁移；V7 的字段与索引已由 V1 当前快照提供，在核对结构后记录为已基线化。初始化独立管理员后，导入基础、扩展及百题目录脚本，共 166 个上线自然题号。V18 启用强制一次性邀请注册，使用方式见 [邀请注册说明](INVITED_REGISTRATION.md)。
- Java 8、Python 3.12、C++17 的 ACM 与核心代码模式实际提交均得到 Accepted，运行时间、内存及核心代码 harness 版本写入 `judgeInfo`。隔离沙箱的 ACM 正常输出、编译错误、运行错误、超时、内存和输出限制验收通过。更高并发和管理员 UI 操作仍应单独验收。
- 阿里云 Agent 与 PostgreSQL 没有公网容器端口。跨云 RabbitMQ 消费、签名回调、DeepSeek `deepseek-flash` 真实分析回写已通过。题目服务的 CSRF 过滤器仅豁免四个仍需 HMAC 验证的 Agent 回调路径；其他写入接口继续要求 CSRF。
- 两端的 `wg-quick@wg0` 与 Docker 已启用开机启动；腾讯云安装 [`systemd/nemo-core.service`](systemd/nemo-core.service)，阿里云安装 [`systemd/nemo-ai.service`](systemd/nemo-ai.service)。两机先后实际重启后，两个单元均自动进入 active，WireGuard 双向连通，核心容器、Agent 消费者与 PostgreSQL 自动恢复。重启后预览页面和语言接口返回 200；Java、Python、C++ 的 ACM 提交再次 Accepted，DeepSeek 分析任务成功回写。RabbitMQ 三个队列均有消费者且 ready/unacknowledged 为 0，沙箱无残留容器，腾讯云 swap 使用量为 0。
- 管理接口通过 18 项真实 HTTP 验收，包括新建题目、发布新版本、下线/重新上线、普通用户权限隔离、提交反馈和管理员回复。应用内浏览器确认管理员可登录，并显示题库管理、反馈处理、AI 巡检和用户管理页面；访客题库显示 166 题且不显示管理导航。测试题目、用户、反馈、提交及对应 MySQL AI 任务已清理；题库恢复为 166 题，测试题的提交统计恢复为 0/0。
- DeepSeek Flash 按当前**峰值**每百万输入 token 0.30 美元、输出 token 1.20 美元估价；阿里云 `.env.agent` 需设置这两个费率。首轮采用每日 1 美元、单任务预留 0.05 美元的站内预算。此估价是本系统的预算控制依据，模型提供方的实际价格和余额仍须独立监控。

若重新部署，分别将上述两个 systemd 单元复制到对应主机的 `/etc/systemd/system/`，执行 `systemctl daemon-reload` 与 `systemctl enable --now nemo-core.service` / `nemo-ai.service`。更新 Compose 镜像后用 `systemctl restart` 应用配置；可用 `systemctl status` 和 `docker compose ps` 检查。

## 地址和防火墙

以下以腾讯云隧道地址 `10.88.0.1`、阿里云隧道地址 `10.88.0.2` 为例。替换公网 IP、SSH 来源和密钥；不要把私钥或 `.env` 提交到仓库。

| 主机 | 公网入站 | 隧道内入口 | 不开放到公网 |
|---|---|---|---|
| 腾讯云 | 80/443、管理者 IP 的 SSH、**仅阿里云公网 IP** 的 UDP 51820 | `10.88.0.1:5672` AMQP，`10.88.0.1:8103` 签名回调代理 | 5672、8103、3306、6379、8848、15672、Docker API |
| 阿里云 | 管理者 IP 的 SSH | 无容器入站 | 5432、Agent 端口、其他数据库端口 |

表中的 80/443 属于后续正式公开发布；SSH 隧道验收时不开放它们。

Docker 发布端口会调整宿主机防火墙规则，不能只依赖 UFW 默认拒绝。应同时在两家云的**安全组/轻量服务器防火墙**按上表限制。腾讯云的 5672/8103 只绑定 WireGuard 地址，但仍必须确认公网 IP 无法访问。

## 1. 建立 WireGuard

两台 Linux 主机分别安装 `wireguard-tools`，分别生成一对密钥。腾讯云 Ubuntu 可用以下命令；阿里云 Alibaba Cloud Linux 使用其对应的包管理器安装：

```bash
sudo apt update && sudo apt install -y wireguard-tools
umask 077
wg genkey | tee ~/wg-private.key | wg pubkey > ~/wg-public.key
cat ~/wg-public.key
```

只交换**公钥**。分别将以下内容保存为 `/etc/wireguard/wg0.conf`，文件权限设为 `600`。尖括号内容由部署者替换；私钥从本机 `wg-private.key` 读取，勿通过聊天或仓库传播。

腾讯云：

```ini
[Interface]
Address = 10.88.0.1/24
ListenPort = 51820
PrivateKey = <腾讯云私钥>

[Peer]
PublicKey = <阿里云公钥>
AllowedIPs = 10.88.0.2/32
```

阿里云：

```ini
[Interface]
Address = 10.88.0.2/24
PrivateKey = <阿里云私钥>

[Peer]
PublicKey = <腾讯云公钥>
Endpoint = <腾讯云公网 IP>:51820
AllowedIPs = 10.88.0.1/32
PersistentKeepalive = 25
```

```bash
sudo chmod 600 /etc/wireguard/wg0.conf
sudo systemctl enable --now wg-quick@wg0
sudo wg show
ping -c 2 10.88.0.1   # 在阿里云上执行；必须看到握手及响应
```

先启动 WireGuard，再启动腾讯云 Compose；否则 `ai-link-proxy` 无法绑定 `10.88.0.1`。重启后也按此顺序检查。两机时钟需同步，签名回调校验依赖时间戳。

## 2. 腾讯云：核心 OJ 与受限代理

在仓库根目录操作，复制 `deploy/.env.example` 为 `deploy/.env` 并填好核心 OJ 的密码、镜像、域名、TLS 等。双机相关值示例：

```dotenv
AI_TUNNEL_BIND_IP=10.88.0.1
AI_INTERNAL_SECRET=<随机且与阿里云相同的签名密钥>
AI_MODEL_PROVIDER=openai
AI_MODEL_ID=<实际模型 ID>
AI_MODEL_ENABLED=true
AI_ANALYSIS_ENABLED=true
AI_LEARNING_ENABLED=true
AI_PRIVATE_PROBLEMS_ENABLED=true
AI_FEEDBACK_ENABLED=true
AI_MAINTENANCE_ENABLED=true
AI_DAILY_BUDGET_USD=<正数预算>
AI_MAX_TASK_BUDGET_USD=<正数预算>
AI_POSTGRES_PASSWORD=NOT_USED_ON_CORE
```

AI 功能开关和预算按实际需要逐项启用；测试初期可保持关闭。`AI_POSTGRES_PASSWORD` 仅为基础 Compose 文件解析所需：叠加层已把本机 PostgreSQL 和 Agent 放入未启用的 `local-ai` profile，腾讯云不会运行它们。**模型 API Key 只填阿里云的 `.env.agent`，不要放到腾讯云配置。**

```bash
cd /srv/nemo-oj
docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml -f deploy/compose.core-cloud.yml config --quiet

# 按 DEPLOY.md 的数据库迁移、镜像和 TLS 顺序完成核心 OJ 部署后：
docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml -f deploy/compose.core-cloud.yml up -d
docker compose --env-file deploy/.env \
  -f deploy/docker-compose.yml -f deploy/compose.core-cloud.yml ps
```

确认 `nemo-agent` 与 `ai-postgres` 没有在腾讯云运行。也不要设置 `COMPOSE_PROFILES=local-ai` 或显式执行 `up nemo-agent`；Compose 显式指定服务会激活其 profile。HAProxy 仅将 AMQP 转发到 RabbitMQ，并只允许题目服务 `POST /api/question/internal/ai/tasks/{id}/{claim|context|complete|fail}`；请求还须通过题目服务的 HMAC 验签。代理配置不代替 WireGuard 和防火墙。

如果腾讯云主机此前用单机 Compose 启动过 Agent，切换前先停止旧 Agent，并备份/迁移其 PostgreSQL 检查点，再启用阿里云 Agent；不要同时运行两份消费者，也不要直接删除旧卷。

## 3. 阿里云：Agent 与 PostgreSQL

在构建机使用仓库中的 `nemo-agent/Dockerfile` 构建镜像，推送到私有仓库，或用 `docker save`/`docker load` 传至阿里云。2 GB 主机建议只拉取或载入成品镜像，不在其上构建：

```bash
# 构建机，版本号使用实际发布版本。若官方 PyPI 不可达，可在可信镜像源构建。
docker build --build-arg PIP_INDEX_URL=https://pypi.org/simple \
  -t nemo-oj/agent:VERSION -f nemo-agent/Dockerfile nemo-agent
docker save nemo-oj/agent:VERSION | gzip > nemo-agent-VERSION.tar.gz

# 阿里云（先通过 scp 传送压缩包）
gunzip -c nemo-agent-VERSION.tar.gz | docker load
```

阿里云仅需 `deploy/compose.agent-cloud.yml` 和 `deploy/.env.agent`。从 [`deploy/.env.agent.example`](.env.agent.example) 复制，并设置为仅当前用户可读。两机必须使用相同的 `AI_INTERNAL_SECRET`；Agent 的 RabbitMQ 用户密码必须与腾讯云 RabbitMQ 账号一致，或使用额外创建且具备 `nemo.ai.tasks` 权限的专用 RabbitMQ 用户。PostgreSQL 密码在连接 URL 中使用，建议 `openssl rand -hex 24` 生成 URL 安全字符。

```bash
cp deploy/.env.agent.example deploy/.env.agent
chmod 600 deploy/.env.agent
vi deploy/.env.agent
docker compose --env-file deploy/.env.agent \
  -f deploy/compose.agent-cloud.yml config --quiet
docker compose --env-file deploy/.env.agent \
  -f deploy/compose.agent-cloud.yml up -d --no-build
docker compose --env-file deploy/.env.agent \
  -f deploy/compose.agent-cloud.yml ps
```

模型若使用兼容 OpenAI 的接口，`AI_MODEL_PROVIDER=openai`，并设置提供方实际支持的 `AI_MODEL_ID` 与 `AI_MODEL_BASE_URL`。2026-10-12 起使用 `qwen3.8-flash` 和用户提供的阿里云 MaaS OpenAI 兼容端点（地址以 `/compatible-mode/v1` 结尾）。Qwen 在阿里云端点上的结构化工作流使用 `extra_body={"enable_thinking": false}`：思考模式不能与强制 `tool_choice` 同时使用，见[官方工具调用说明](https://help.aliyun.com/zh/model-studio/qwen-function-calling)。腾讯云同步更新 `AI_MODEL_ID`，避免任务和分析记录仍标记为旧模型；密钥只填阿里云私有的 `.env.agent`。费用估算单价需要按该端点的实际计费单价单独配置，切换模型不会自动替换单价。历史 DeepSeek 配置使用 `deepseek-flash` 和 `https://api.deepseek.com`，仍保留兼容支持。不要把 API Key 写入仓库或腾讯云配置。`ai-private` 网络只给 Agent 和 PostgreSQL 使用；Agent 通过 `ai-egress` 出站访问 WireGuard 对端与模型 API。Compose 不发布任何阿里云容器端口。

## 4. 连通与权限验证

在阿里云主机检查隧道和两个 TCP 入口：

```bash
sudo wg show
ping -c 2 10.88.0.1
timeout 3 bash -c '</dev/tcp/10.88.0.1/5672' && echo 'AMQP TCP OK'
curl -sS -o /dev/null -w '%{http_code}\n' \
  http://10.88.0.1:8103/api/question/list/page
# 预期 403：普通题目接口被 HAProxy 拦截
```

在腾讯云执行 `docker compose ... logs --tail=100 ai-link-proxy`，在阿里云执行 `docker compose ... logs --tail=100 nemo-agent`；然后提交一条测试反馈或触发一次代码分析，确认 `nemo.ai.tasks` 的消费者出现、任务进入终态、Agent 能回写，且 PostgreSQL 检查点产生。不要把包含凭据或用户代码的原始日志发到公共渠道。

最后从**腾讯云公网 IP** 检查 5672/8103 不可访问，并检查阿里云公网 5432 不可访问。`sudo ss -lntp` 在腾讯云应显示 5672/8103 只绑定 `10.88.0.1`。真实两云验收前，不把同机虚拟机测试视为跨云已通过。

## 停机和回退

若 AI 链路故障，先在腾讯云关闭 `AI_*_ENABLED` 的业务开关并重启题目服务，保留核心刷题与判题；然后在阿里云停止 Agent。不要删除 PostgreSQL 卷，避免丢失 LangGraph 检查点。核心 OJ 原有 Compose 和数据库不需要因 AI 链路故障整体下线。
