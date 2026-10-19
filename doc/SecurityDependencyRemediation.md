# Java 依赖风险修复与临时防护

日期：2026-10-19。范围为源码、构建产物的依赖解析和 GitHub CI；不代表线上旧镜像已经更新。

## 兼容升级

维持 JDK 17、Spring Boot 3.5.16、Spring Cloud 2025.0.3，不混装 Spring 6 与 Spring 7。
微服务父 POM 和独立沙箱 POM 使用相同的安全版本覆盖；BOM 家族一起对齐，避免仅升级一个 jar。

| 依赖 | 原版本 | 目标版本 |
| --- | --- | --- |
| Jackson core/databind | 2.21.4 | BOM 2.21.8 |
| Netty | 4.1.135.Final | BOM 4.1.139.Final |
| Tomcat embed | 10.1.55 | 10.1.61 |
| Bouncy Castle | 1.80.2 | 1.86 |
| RabbitMQ Java client | 5.25.0 | 5.37.0 |
| Log4j API | 2.24.3 | BOM 2.26.1 |
| Apache HttpClient5 / HttpCore5 | 5.5.2 / 5.3.6 | 5.6.3 / 5.4.3 |
| 沙箱 Commons Lang | 3.17.0 | 3.20.0 |

基准报告有 46 条漏洞记录；记录数包含同一漏洞在不同组件上的重复项，不等于可利用漏洞数量。
移除子模块重复导入的 Boot/Cloud BOM，统一继承父 POM 的安全覆盖。沙箱将 docker-java 聚合包改为 core + 已选用的 HTTP5 transport，移除未使用的旧 Jersey/HttpClient4 传输依赖。

修复版本依据 OSV 的具体条目与 Maven Central 已发布制品核对；最终以 CI 导出的真实依赖清单重新查询为准。

## Spring 风险：已缓解，版本尚未修复

用户选择先保持兼容，框架大迁移另行安排。以下条目仍保留在原始 `osv-report.json`，不删除、不降低严重度，也不宣称组件漏洞已修复：

- CVE-2026-47884 / GHSA-pc63-qcmh-9cmg：spring-webmvc 的 XSLT 视图入口。
- CVE-2026-47890 / GHSA-j9f9-w8pj-32f8：spring-webmvc、spring-webflux 的 HTML 片段 SSE 渲染，各一条记录。
- CVE-2026-47892 / GHSA-9qf2-26p9-2q2q：混用 Servlet 与函数式 WebFlux 的入口。

官方公告：[XSLT](https://spring.io/security/cve-2026-47884/)、[片段 SSE](https://spring.io/security/cve-2026-47890/)、[Header Predicate](https://spring.io/security/cve-2026-47892/)。
官方给出的免费修复线为 Spring 7.0.9；Spring 6.2.20 属于企业支持。本项目没有企业仓库授权，不能通过改版本号假装获得该补丁。

当前防护：

1. Servlet 服务与沙箱启用 `RestApiRenderingGuard`，启动时拒绝 XSLT 视图/解析器和函数式路由；同时拒绝通用解析器指向 XSLT。
2. 包装 MVC 返回值处理器：拒绝显式/隐式服务端视图与 HTML 片段响应，返回 406；保留 JSON、异步 JSON 和现有 JSON SSE。前端静态页面仍由 Nginx 提供。
3. 网关启用 `ReactiveSurfaceGuard`，拒绝引入 `DispatcherServlet`，保持响应式网关架构。
4. CI 检查业务源码不能引入片段渲染、XSLT 或函数式视图入口。此约束不替代代码审查，尤其不能据此允许运行任意动态加载的视图代码。
5. 回归测试覆盖视图拒绝、XSLT 解析器、函数式路由拒绝、正常 JSON/异步 JSON 和 SSE 换行注入。

这些控制针对当前应用使用方式降低触发风险，不能替代厂商版本修复。若需要服务端模板、函数式路由或新的流式渲染，应先重新评审并完成框架升级。

## CI 门禁与复核

扫描逐模块导出 7 个微服务模块与独立沙箱的 Java 运行时依赖，保留依赖清单、`runtime-inventory.json`、原始 OSV 报告和 `security-triage.json`。缺少模块、清单为空、网关/沙箱关键依赖缺失或 OSV 返回数量不完整均失败，防止漏扫被误认为安全。
它不覆盖容器操作系统、语言运行时镜像、Python 或 npm 依赖，不能宣称整个平台无漏洞。

`deploy/security/risk-register.json` 只登记上述四个精确的组件、版本和漏洞编号。
新记录（包括 LOW）、版本改变、控制缺失、源码引入受限入口，或复核到期都会使安全门禁失败。
本次复核有效期至 **2026-11-18（UTC 日期）**，届时下一次 CI 必须重新评审；没有设置定时通知。

大迁移任务保留为：Spring Boot 4 / Spring 7、对应 Spring Cloud 与 MyBatis starter 兼容、Jackson 迁移，以及全部业务与部署验收。

## 本地复扫证据

2026-10-19：8 个模块合计解析到 **169 个去重的外部运行时依赖**。
原报告 46 条记录中 42 条已通过升级或移除不使用的依赖消除；新报告剩余 **4 条**，均为上文明确跟踪的 Spring 风险。另修复本次扩展沙箱扫描新查出的 HTTP/Commons Lang 风险。
原始报告仍将这 4 条标为 CRITICAL；分流报告记录为“已缓解，版本未修复”，未评审记录为 0。
此结果针对本次 OSV 数据与解析到的依赖，不代表未知漏洞或未覆盖技术栈无风险。

## 发布说明

本轮不自动部署或重启腾讯云、阿里云服务。线上仍应按当前镜像版本评估，不能用源码扫描结果替代线上验收。
部署时须重新构建后端与沙箱镜像，排空任务后逐步发布，复核登录、提交、错误诊断、SSE、权限和三语言判题。
回滚至旧镜像也会回到旧依赖风险，不能在回滚后继续沿用新版本的安全结论。
