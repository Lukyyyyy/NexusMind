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
