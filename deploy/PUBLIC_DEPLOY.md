# fw-nemo.cn 公网 HTTPS 启用

本指南用于已有的腾讯云核心 OJ + 阿里云 AI 双机部署。2026-10-08 已完成公网 HTTPS 部署，入口为 `https://fw-nemo.cn/problems`。备案完成后，经用户授权恢复已有账号登录和代码提交，注册继续关闭。阿里云 AI 无需添加公网 Web 端口。

当前前端使用 `VITE_SITE_MODE=normal`、`VITE_REGISTRATION_ENABLED=false` 构建，注册页面及入口不加载。Nginx 只拦截 `/api/user/register`，访问 `/register` 重定向至 `/login`；后端保持 `REGISTRATION_ENABLED=false`，包括 SSH 私有验收入口也不能注册。恢复登录不需要重启数据库、判题或 AI 服务。重新构建前端时必须保留上述配置，更新静态资源后验证登录、实际判题、提交详情和注册拦截。

## 1. 腾讯云控制台放行 Web 端口

进入轻量应用服务器控制台，找到公网 IP **43.142.118.220** 的现有实例，打开「防火墙」→「添加规则」：

| 应用类型 | 协议 | 端口 | 来源 | 策略 |
|---|---|---|---|---|
| 自定义 | TCP | 80,443 | 0.0.0.0/0 | 允许 |

在当前实例上添加规则，不要覆盖或替换原规则。保留 SSH TCP 22 和 WireGuard UDP 51820 的现有来源限制。数据库、RabbitMQ、业务服务和 SSH 预览端口不需要公网放行。

添加步骤与规则格式见 [腾讯云管理实例防火墙](https://cloud.tencent.com/document/product/1207/44577)。

## 2. 准备本指南配套文件

需将以下文件同步至腾讯云 `/srv/nemo-oj/` 下相同的相对路径：

- `deploy/nginx/oj.conf`
- `deploy/compose.core-public.yml`
- `deploy/systemd/nemo-public.service`
- `deploy/ops/reload_public_certificate.sh`

保留服务器现有 `deploy/.env`，不要用示例环境文件覆盖它。前端发布使用上述独立开关构建的产物，先添加新的哈希资源，再原子替换 `index.html`，保留旧哈希资源供已打开页面使用。公网 Compose 叠加层补齐前端文件与 ACME 校验目录挂载，并将新代理内存限制为 64 MiB。

以下命令均在**腾讯云 Ubuntu 的 SSH 终端**执行。

```bash
cd /srv/nemo-oj
sudo install -d -m 755 deploy/nginx/acme
sudo install -d -m 700 deploy/nginx/certs
sudo snap install --classic certbot
/snap/bin/certbot --version
```

如果 Certbot 已安装，不需要重新安装。此流程使用 Certbot 2.3 或更高版本的 `reconfigure` 命令。不要安装宿主机 Nginx；现有项目使用 Docker 内的 Nginx。

## 3. 首次签发证书

已确认 `fw-nemo.cn` 的 A 记录指向 `43.142.118.220`。签发前再次检查，且此时宿主机 80 端口应没有监听服务：

```bash
getent ahostsv4 fw-nemo.cn
sudo ss -ltnp '( sport = :80 or sport = :443 )'
sudo /snap/bin/certbot certonly --standalone --cert-name fw-nemo.cn -d fw-nemo.cn
```

Certbot 会交互询问通知邮箱与服务条款，按提示完成。它临时监听 80 端口以验证域名，结束后释放端口，不影响回环地址上的 SSH 预览。若发现已有服务占用 80，不要直接停止它，应先确认服务用途。

若签发报连接超时，首先核对云防火墙放行、A 记录和是否存在错误的 AAAA 记录。HTTP-01 校验需要公网可访问 80，详见 [Certbot 官方说明](https://eff-certbot.readthedocs.io/en/stable/using.html#standalone)。

```bash
sudo bash deploy/ops/reload_public_certificate.sh
```

该脚本校验证书域名、有效期和私钥匹配后，安装到现有公网代理要求的路径。不要把证书私钥、ACME 账号或真实 `.env` 放入仓库。

## 4. 更新域名与网关来源

使用 `sudoedit deploy/.env` 编辑服务器现有环境文件，仅调整下面两个字段；不要打印整个文件：

```dotenv
PUBLIC_DOMAIN=fw-nemo.cn
CORS_ALLOWED_ORIGINS=https://fw-nemo.cn,http://127.0.0.1:18080
```

`REGISTRATION_ENABLED=false` 保持当前值。网关支持逗号分隔的明确来源；保留 SSH 预览来源，方便私有验收。

使用 `sudoedit deploy/nginx/oj.conf`，将 HTTPS server 中的 `server_name oj.example.com;` 改为 `server_name fw-nemo.cn;`。`PUBLIC_DOMAIN` 是部署记录变量，**不会自动替换这个静态 Nginx 文件**。

准备网关与公网代理的 Compose 命令（只对当前终端有效）：

```bash
compose=(sudo docker compose --env-file deploy/.env
  -f deploy/docker-compose.yml
  -f deploy/compose.core-cloud.yml
  -f deploy/compose.core-4g-preview.yml
  -f deploy/compose.core-public.yml
  --profile public)

"${compose[@]}" config --quiet
"${compose[@]}" run --rm --no-deps proxy nginx -t
"${compose[@]}" up -d --no-build --no-deps gateway
"${compose[@]}" ps gateway
```

`nginx -t` 只校验配置，不发布端口。更新网关会有短暂 API 中断，等待其状态恢复 healthy；不重建镜像或重启 MySQL、判题、沙箱及 AI。

启用独立公网入口单元，使主机重启后自动恢复：

```bash
sudo install -m 644 deploy/systemd/nemo-public.service /etc/systemd/system/nemo-public.service
sudo systemctl daemon-reload
sudo systemctl enable --now nemo-public.service
"${compose[@]}" ps proxy
```

该单元只管理公网代理，并依赖现有 `nemo-core.service`；核心单元重启时它也会跟随重启。核心服务单元继续使用原有三个 Compose 文件。不要执行 Compose `down`，否则会停止整套服务。

## 5. 自动续期与验证

首次签发使用 standalone；正式代理启动后改用 webroot，避免以后续期抢占 80：

```bash
sudo /snap/bin/certbot reconfigure --cert-name fw-nemo.cn --webroot -w /srv/nemo-oj/deploy/nginx/acme
sudo install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
sudo install -m 700 deploy/ops/reload_public_certificate.sh /etc/letsencrypt/renewal-hooks/deploy/nemooj
sudo /snap/bin/certbot renew --cert-name fw-nemo.cn --dry-run --run-deploy-hooks
systemctl list-timers --all 'snap.certbot.*'
```

确认测试续期成功且 Certbot 定时器存在。续期成功后 hook 会复制新的证书并先运行 `nginx -t`，再热加载代理。`reconfigure` 的行为见 [Certbot 官方说明](https://eff-certbot.readthedocs.io/en/stable/using.html#modifying-the-renewal-configuration-of-existing-certificates)。

在服务器和你自己的电脑上分别访问，不使用 `-k` 跳过证书校验：

```bash
curl -I http://fw-nemo.cn/problems
curl -I https://fw-nemo.cn/problems
curl -I https://fw-nemo.cn/login
curl -I https://fw-nemo.cn/api/question/internal/example
```

预期 HTTP 跳转 HTTPS，HTTPS 题库返回 200，登录页跳转题库，内部接口返回 403。浏览器应能显示 166 题、题目详情和底部备案号，没有证书警告及登录注册入口。再检查公开题库 API 在域名来源下成功，HTTPS 资产加载正常，SSH 预览仍可用。

如果公网不通而服务器内部访问正常，检查云防火墙；如果显示 502，检查网关是否 healthy；如果 API 返回跨域/来源拒绝，核对 `CORS_ALLOWED_ORIGINS` 并确认网关已重建容器；如果 Nginx 不启动，检查证书路径与 `nginx -t` 输出。

## 仅关闭公网入口

```bash
sudo systemctl disable --now nemo-public.service
```

此操作只停止公网代理，保留核心 OJ、跨云 AI 和 SSH 预览。需要立即阻止公网访问时，可同时在腾讯云控制台移除本次添加的 80/443 规则，保留其他现有规则。
