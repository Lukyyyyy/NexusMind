# 知枢 NexusMind

知枢 NexusMind 是一个面向企业和团队知识管理场景的 AI 知识库系统，基于 RAG（Retrieval-Augmented Generation）流程实现文档上传、解析、向量化、检索和问答生成。

## 核心能力

- 文档上传、分片合并、解析与索引
- 基于 Elasticsearch 的关键词检索与向量检索
- 基于组织标签的知识库隔离与权限控制
- WebSocket 聊天交互与会话历史管理
- Kafka 异步文档处理
- MinIO 文件对象存储
- Spring Security + JWT 用户认证

## 技术栈

后端：Spring Boot 3.4、Java 17、Spring Data JPA、Spring Security、WebFlux、Kafka、Redis、Elasticsearch、MinIO、Apache Tika。

前端：Vue 3、TypeScript、Vite、Naive UI、Pinia、Vue Router、UnoCSS、SCSS。

## 项目结构

```text
backend/
├── pom.xml       # 后端 Maven 工程
├── docs/         # Docker Compose、Nginx、数据库脚本等后端配套文件
└── src/          # Spring Boot 源码与测试

backend/src/main/java/com/luky/nexusmind/
├── client/        # AI 与外部服务客户端
├── config/        # Spring、安全、缓存、消息队列等配置
├── consumer/      # Kafka 消费者
├── controller/    # REST API
├── entity/        # 检索与消息实体
├── exception/     # 自定义异常
├── handler/       # WebSocket 处理器
├── model/         # JPA 领域模型
├── repository/    # 数据访问层
├── service/       # 业务服务
└── utils/         # 工具类
```

```text
frontend/
├── packages/      # 前端工作区共享包
├── public/        # 静态资源
└── src/
    ├── components/
    ├── layouts/
    ├── router/
    ├── service/
    ├── store/
    └── views/
```

`homepage/` 是独立静态产品介绍页，不参与主前端应用运行。主应用入口在 `frontend/`。

## 环境要求

- Java 17
- Maven 3.8.6+
- Node.js 18.20.0+
- pnpm 8.7.0+
- MySQL 8.0
- Elasticsearch 8.10.0
- MinIO 8.5.12
- Kafka 3.2.1
- Redis 7.0.11
- Neo4j 5.26（知识图谱）

## 本地启动

首次使用时复制环境变量模板：

```bash
cp .env.example .env.local
```

按需填写 `.env.local` 中的 `DEEPSEEK_API_KEY` 和 `EMBEDDING_API_KEY`。不填写时项目仍可启动，但 AI 问答和文档向量化不可用。

一键启动开发环境：

```bash
./scripts/start-dev.sh
```

该脚本会启动 Docker 中间件，并同时启动后端和前端。

只启动中间件：

```bash
./scripts/start-infra.sh
```

只启动后端：

```bash
./scripts/start-backend.sh
```

只启动前端：

```bash
./scripts/start-frontend.sh
```

后端默认端口为 `18081`，前端测试环境默认请求 `http://localhost:18081/api/v1`。

## 服务器部署

服务器部署使用独立的 `.env.deploy.local`，不会覆盖本地开发使用的 `.env.local`：

```bash
./scripts/prepare-deployment.sh
```

准备脚本不会覆盖已有 `.env.deploy.local`。已有部署应参照 `.env.deploy.example` 补齐缺失字段，并设置 `APP_PUBLIC_URL=https://nexusmind.lukybetter.com`；不要重新生成现有密码。

首次生成或更新后，至少确认以下配置：

- `APP_PUBLIC_URL`：用户访问知枢 NexusMind 的公网地址。
- 文件下载经 NexusMind 后端代理，MinIO 只需内网可达，不需要公网域名或新增开放端口。下载接口保留 `downloadUrl` 字段，返回默认有效期 1 小时的文件级票据链接，与原预签名链接保持一致；票据过期后需重新申请。通过 `FILE_DOWNLOAD_TICKET_TTL` 可自定义有效期（如 `30m`、`1h`），必须大于 0。重启后配置对新签发票据生效，已有票据的过期时间不变。
- `REDIS_PASSWORD`：必须与服务器已有 Redis 的认证密码一致；无密码时留空。
- `DEEPSEEK_API_KEY`、`EMBEDDING_API_KEY`：也可以在系统的模型配置页面中设置。
- 邮件使用 SMTP 时在管理页面配置；使用腾讯云 SES 时设置 `MAIL_PROVIDER=tencent-ses` 并填写全部 `TENCENT_SES_*` 参数。

首次启动全部服务器容器：

```bash
docker compose --env-file .env.deploy.local -f docker-compose.deploy.yml up -d --build
```

代码拉取到服务器后，使用一键脚本选择性重新部署。脚本不会操作 Git 或数据卷；新镜像构建成功后才替换线上容器，健康检查失败时自动恢复本次操作前的镜像。前端本地构建期间会临时暂停 MinerU：

```bash
./scripts/deploy.sh backend
./scripts/deploy.sh frontend
./scripts/deploy.sh all
```

需要强制重新安装依赖并完整重建镜像时，可在部署命令末尾添加 `--no-cache`：

```bash
./scripts/deploy.sh frontend --no-cache
./scripts/deploy.sh all --no-cache
```

手动恢复最近一个历史版本：

```bash
./scripts/deploy.sh rollback backend
./scripts/deploy.sh rollback frontend
./scripts/deploy.sh rollback all
```

`all` 将前后端作为同一个部署事务处理，任一服务失败都会恢复本次部署前已经替换的服务。每个服务保留 `local` 当前版本和最近两个 `rollback-*` 历史版本；清理范围仅限 NexusMind 前后端镜像。容器与镜像支持自动恢复，Flyway 已执行的数据库迁移不会由脚本自动撤销。
重新部署要求服务器已安装 Docker Buildx，并且构建开始前具有足够可用内存。涉及前端本地构建时，脚本会临时停止 MinerU 以释放内存，并在构建成功、失败或被中断后重新启动；暂停期间文档解析服务暂不可用。脚本会拒绝与其他 Docker 构建任务同时运行，不会清理系统缓存、重置 Swap 或执行全局 Docker 清理。


Redis 是否需要密码应使用 `sudo docker exec redis redis-cli ping` 检查：返回 `PONG` 表示当前默认连接无需认证，`NOAUTH` 表示需要密码。宿主机 `6379` 未监听并不意味着容器故障，不需要为此向公网映射 Redis 端口。

后台 Kafka 解析继续使用 MinIO 内网预签名 URL，不经浏览器下载接口。下载票据是短期访问凭据，请勿记录完整查询字符串或分享链接；已签发票据在到期前可访问对应文件。

服务器编排复用名为 `mysql`、`redis`、`minio` 的既有容器，并要求存在 `server_proxy` 外部网络；准备脚本会创建并连接 `shared_services` 网络。MinerU 默认采用 CPU pipeline，GPU 开发覆盖文件不参与服务器部署。

## 配置说明

敏感配置通过环境变量注入，避免在仓库中保存真实密钥：

- `JWT_SECRET_KEY`
- `MYSQL_PASSWORD`
- `MYSQL_HOST_PORT`
- `MYSQL_PORT`
- `MINIO_ACCESS_KEY`
- `MINIO_SECRET_KEY`
- `DEEPSEEK_API_KEY`
- `EMBEDDING_API_KEY`
- `LANGFUSE_PUBLIC_KEY`
- `LANGFUSE_SECRET_KEY`
- `BACKEND_PORT`
- `KNOWLEDGE_GRAPH_ENABLED`
- `NEO4J_URI`
- `NEO4J_USERNAME`
- `NEO4J_PASSWORD`

知识图谱默认通过环境变量启用。文档解析完成后会异步生成候选关系；上传者在知识库文件的“更多 → 确认图谱关系”中审核发布，发布后的关系会自动参与聊天检索。

图谱采用“一套 Neo4j、多个逻辑空间”的方式存储：公开文档进入 `PUBLIC`，组织文档进入 `ORG:<组织标识>`，私人文档进入 `USER:<用户标识>`。同名同类型实体只在相同空间内合并；问答时按用户当前有权访问的文档，自动组合公共、所属组织和个人图谱。

## 常用命令

```bash
cd backend
mvn test
mvn clean package
```

```bash
cd frontend
pnpm typecheck
pnpm build
```
