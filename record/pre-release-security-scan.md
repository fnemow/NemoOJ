# NemoOJ 依赖安全扫描报告

扫描日期：2026-09-17
扫描工具：`build/osv-scan.py`（OSV API，聚合 GitHub Security Advisories / NVD / GHSA）
扫描范围：`OJ-backend-microservice` 与 `nemo-sandbox` 的 compile + runtime 依赖（不含 `com.nemo` 内部模块）

> **执行结果（2026-09-17 更新）**：依据本文第 2、3 节的实测数据，已决定并完成
> Spring Boot 3.5.16 + Java 17 迁移（详见 `record/log/2026-09-17-14-30-migrate-spring-boot-3.md`）。
> 迁移后重新扫描：**唯一漏洞 ID 由 192 降至 36（-81%），CRITICAL 由 23 降至 5，HIGH 由 129 降至 11**。
> 完整结果见 `build/scan-tmp/osv-report-final.json`。

> 说明：本报告只陈述 OSV 收录的事实与版本可用性，不判断具体代码路径是否可利用。
> "无同线修复版"指修复版本均已跨出当前主次版本线（例如 5.3.x 需升到 6.x），
> 在不做大版本迁移的前提下无法通过升级该依赖消除。

## 1. 原始结果（当前状态）

依赖树：Spring Boot 2.6.13（微服务）+ 2.7.2（沙箱）

| 指标 | 数量 |
|---|---|
| 漏洞记录 | 336 条（两模块合计） |
| 去重漏洞 ID | 192 个 |
| 涉及依赖（坐标+版本） | 64 个 |
| CRITICAL | 23 条 |
| HIGH | 129 条 |
| MODERATE | 154 条 |
| LOW | 30 条 |

绝大多数记录来自 Spring Boot BOM 传递管理的组件（Tomcat、Spring Framework、Jackson、Netty、Logback、SnakeYAML）。

## 2. 方案对比（实测，非估算）

同一扫描口径下，对 `OJ-backend-microservice` 依赖树分别测量：

| 方案 | 去重漏洞 ID | CRITICAL | HIGH | 迁移成本 |
|---|---|---|---|---|
| Boot 2.6.13（现状） | 177 | 13 | 74 | — |
| Boot 2.7.18（BOM 自然升级） | 168 | 12 | 69 | 低 |
| **Boot 2.6.13 + 定向补丁覆盖** | **70** | **3** | **16** | **低** |
| Boot 3.2.12 | 133 | 12 | 52 | 高 |
| Boot 3.5.13 | 97 | 11 | 35 | 高 |
| **Boot 3.5.13 + 最新补丁** | **22** | **4** | **8** | **高（Jakarta 迁移）** |

"定向补丁覆盖"指保持 Spring Boot 2.6.13，仅把这些 BOM 管理属性指向同线最新补丁版本：

```
tomcat.version=9.0.122          spring-framework.version=5.3.39
jackson-bom.version=2.22.2      netty.version=4.1.138.Final
logback.version=1.2.13          snakeyaml.version=1.33
```

## 3. 关键结论

1. **只升级 Spring Boot 小版本几乎没有收益。** 2.6.13 → 2.7.18 实测仅从 177 降到 168，
   CRITICAL 从 13 降到 12。原因是 2.7.18 的 BOM 同样停留在 2023 年的组件版本。
2. **收益主要来自"组件是否处于最新补丁版"**，而不是主版本号。
   Boot 3.2.12 甚至比打了补丁的 2.6.13 更差（133 vs 70）。
3. 因此对当前 Java 8 项目，**定向补丁覆盖是性价比最高的做法**：
   漏洞 ID 下降 60%，CRITICAL 由 13 降至 3，无需 Jakarta 迁移。
4. 完全消除需要 Boot 3.5.x + 最新补丁（22 个 ID），但代价是 Java 17 + `javax.*` → `jakarta.*` 全量迁移，
   涉及所有模块的 Controller/Filter/Servlet API、Spring Security 6、Spring Cloud Gateway 4.x、knife4j 文档组件。
   该迁移应作为独立任务立项，不建议与本次上线合并。

## 4. 定向覆盖后仍然残留的 CRITICAL

| 依赖 | 当前版本 | 问题 | 处理 |
|---|---|---|---|
| `cn.hutool:hutool-all` | 5.8.8 | 2 条 CRITICAL，修复版 5.8.21 | 直接升级版本即可（父 POM 管理） |
| `com.baomidou:mybatis-plus` | 3.5.2 | 1 条 CRITICAL，修复版 3.5.3.1 | 直接升级版本即可（父 POM 管理） |
| `org.springframework:spring-web` | 5.3.39 | 2 条 CRITICAL 无 5.3.x 修复版 | 只能随 Boot 3.x 消除，需评估实际利用条件 |

## 4.1 迁移后残余项（2026-09-17 实测）

当前依赖树（Boot 3.5.16 管理的版本）剩余 36 个唯一漏洞 ID，集中在：

| 依赖 | 版本 | 严重度 | 说明 |
|---|---|---|---|
| `org.apache.tomcat.embed:tomcat-embed-core` | 10.1.55 | CRITICAL | 最新 10.1.x 为 10.1.60 |
| `org.bouncycastle:bcprov-jdk18on` | 1.82 | CRITICAL/HIGH | Boot BOM 管理版本 |
| `io.netty:netty-handler` 等 | 4.1.135.Final | CRITICAL/HIGH | 最新 4.1.x 为 4.1.138.Final |
| `com.rabbitmq:amqp-client` | 5.25.0 | HIGH | |
| `org.apache.httpcomponents.core5:httpcore5` | 5.3.6 | HIGH | |

这些是 **Boot 3.5.16 BOM 管理的版本落后于各组件最新补丁版** 造成的，与主版本无关。
实测把上述组件再覆盖到各自最新补丁版后，唯一 ID 可进一步降到 **22 个**。
是否做这层覆盖属于发布策略选择：收益有限（36 → 22），但会引入 BOM 之外的版本组合，
需要在回归测试中覆盖。建议作为独立的后续任务评估。

## 5. 使用方式

```bash
# 1. 导出依赖清单
mvn -s build/maven-settings.xml dependency:list -DincludeScope=runtime \
    -DoutputFile=deps.txt -DappendOutput=true

# 2. 扫描（需要联网访问 api.osv.dev）
python build/osv-scan.py deps.txt
# 结果输出到 stdout，完整数据写入 osv-report.json
```

CI 中也可用 OWASP dependency-check（已在父 POM 的 `security-scan` profile 中配置），
但该插件首次需同步 NVD 数据库，体积与耗时都远大于本脚本，建议作为发布前的补充手段。

## 6. 证据文件

| 文件 | 内容 |
|---|---|
| `osv-report.json` | 当前依赖树的完整扫描结果 |
| `build/scan-tmp/osv-report-2613.json` | Boot 2.6.13 基线 |
| `build/scan-tmp/osv-report-2718.json` | Boot 2.7.18 |
| `build/scan-tmp/osv-report-patched.json` | Boot 2.6.13 + 定向补丁 |
| `build/scan-tmp/osv-report-boot35.json` | Boot 3.5.13 |
| `build/scan-tmp/deps-*.txt` | 各方案解析出的依赖清单 |
