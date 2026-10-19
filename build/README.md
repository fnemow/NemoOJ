# 构建说明

本文档描述 NemoOJ 的可复现构建方式。上线范围见 `record/pre-release-agent-plan.md`。

## 1. 构建基线

| 项 | 版本 | 说明 |
|---|---|---|
| JDK | **17**（本项目实测 17.0.1） | 应用与沙箱均编译到 Java 17 |
| Maven | 3.6.3+ | 本机实测 3.6.3 |
| Spring Boot | 3.5.16 | |
| Spring Cloud | 2025.0.3 | |
| Spring Cloud Alibaba | 2025.0.0.0 | |
| 沙箱内 Java 运行时 | 由判题镜像决定（`sandbox.image.java`） | 与被判题代码的执行环境相关，见 T08 |

> 注意：**构建 JDK 与沙箱内运行 JDK 是两个不同的版本**。
> 前者编译本项目（17），后者执行用户提交的代码（镜像内的 Java）。

## 2. 环境准备

本机 Maven 全局 `settings.xml` 配置的 alimaven 镜像使用的是旧版 HTTP 端点
（`http://maven.aliyun.com/nexus/content/groups/public/`），在部分网络下会 443 超时导致构建中断。
仓库内提供了项目本地设置文件：

```bash
mvn -s build/maven-settings.xml -B clean package
```

`build/maven-settings.xml` 使用阿里云 HTTPS 公共仓库（Central 的完整代理），
并配置了下载重试。若构建环境无法访问阿里云，把其中的 `<url>` 换成
`https://repo1.maven.org/maven2/` 即可。该文件不含任何凭据，可提交到版本库。

## 3. 构建命令

### 微服务

```bash
cd OJ-backend-microservice
mvn -s ../build/maven-settings.xml -B clean package
```

产物（`target/` 下）：

| 模块 | 产物 | 说明 |
|---|---|---|
| OJ-backend-gateway | 可执行 JAR | 响应式网关（Netty） |
| OJ-backend-user-service | 可执行 JAR | |
| OJ-backend-question-service | 可执行 JAR | |
| OJ-backend-judge-service | 可执行 JAR | 不访问数据库 |
| OJ-backend-common | 普通 JAR | 共享库，**不**重打包 |
| OJ-backend-model | 普通 JAR | 共享库，**不**重打包 |
| OJ-backend-service-client | 普通 JAR | 共享库，**不**重打包 |

库模块不声明 `spring-boot-maven-plugin`，因此始终产出普通依赖 JAR；
应用模块显式把 `skip` 置为 `false` 才会 repackage。

### 沙箱

```bash
cd nemo-sandbox
mvn -s ../build/maven-settings.xml -B clean package
```

沙箱是纯计算服务，不依赖数据库；不要为它添加 MyBatis/JDBC 依赖
（会触发 `DataSourceAutoConfiguration` 导致启动失败）。

## 4. 测试分层

| 层次 | 命名 | 执行方式 | 依赖 |
|---|---|---|---|
| 单元测试 | `*Test.java` | `mvn test`（默认执行） | 无外部依赖 |
| 集成测试 | `*IT.java` | `mvn -P integration verify` | 真实 MySQL / Redis / RabbitMQ / Nacos |
| Linux 沙箱测试 | 待补（T08） | 见 T08 | 真实 Docker daemon + 判题镜像 |

```bash
# 只跑单元测试（默认）
mvn -s build/maven-settings.xml -B test

# 跑集成测试（需要中间件就绪）
mvn -s build/maven-settings.xml -B -P integration verify
```

集成测试需要独立测试库与口令，且该库须已执行过 `deploy/migration`：

```bash
# 准备测试库
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS nemo_oj_it \
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
MYSQL_PWD='<口令>' deploy/migration/apply_migrations.sh \
  --host 127.0.0.1 --user root --database nemo_oj_it

# 运行（口令通过环境变量提供，连接信息见 application-it.yml）
IT_DB_PASSWORD='<口令>' mvn -s build/maven-settings.xml -B -P integration verify
```

> 注意：本项目只导入 Spring Boot BOM、没有继承 `spring-boot-starter-parent`。
> 由于 `repackage` 会把主制品替换成可执行 JAR（类在 `BOOT-INF/classes/`，不在普通类路径上），
> failsafe 必须显式设置 `<classesDirectory>${project.build.outputDirectory}</classesDirectory>`，
> 否则集成测试会报 `ClassNotFoundException`。该配置已在父 POM 中提供。

**不要依赖"构建成功"判断测试通过**：Maven 3.6.x 默认使用 surefire 2.12.4，
它不支持 JUnit 5，会输出 `Tests run: 0` 且仍然 `BUILD SUCCESS`。
本项目已在 POM 中显式固定 surefire/failsafe 版本，请通过日志中的
`Tests run:` 行核对实际执行数量。

## 5. 依赖安全扫描

```bash
# 1) 导出依赖清单
cd OJ-backend-microservice
mvn -s ../build/maven-settings.xml -B dependency:list -DincludeScope=runtime \
    -DoutputFile=deps.txt -DappendOutput=true

# 2) 扫描（需访问 api.osv.dev）
cd ..
python build/osv-scan.py deps.txt
```

结果写入 `osv-report.json`。历史扫描结论见 `record/pre-release-security-scan.md`。

## 6. 本地联调

```powershell
# 重新构建前，先停止仍占用 target 下 JAR 的本地服务（Windows 会锁定文件）
pwsh -File build/stop-local-services.ps1
```

### 本地必需的开发环境变量

仓库内**不保存任何数据库口令**。dev profile 的 `DB_PASSWORD` 没有默认值，
本地启动前必须自行提供（环境变量或 IDE 运行配置）：

```bash
export DB_PASSWORD='<你的本机 MySQL 口令>'      # Windows PowerShell: $env:DB_PASSWORD='...'
```

其它可选覆盖项（均有本地默认值）：`DB_URL`、`DB_USERNAME`、`REDIS_HOST`、`REDIS_PORT`、
`RABBITMQ_HOST`、`NACOS_SERVER_ADDR`、`SERVER_PORT`。

启动示例（跳过 Nacos，直连本机中间件）：

```bash
DB_PASSWORD='<口令>' java -jar OJ-backend-user-service/target/OJ-backend-user-service-0.0.1-SNAPSHOT.jar \
  --spring.cloud.nacos.discovery.enabled=false \
  --spring.datasource.url="jdbc:mysql://localhost:3306/nemo_oj_verify"
```

> 联调请使用独立数据库（如 `nemo_oj_verify`），不要直连开发库或生产库。
