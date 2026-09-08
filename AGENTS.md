# AGENTS.md

本仓库为知枢 NexusMind，是一个基于 Spring Boot 和 Vue 3 的 AI 知识库系统。

## 常用命令

后端：

```bash
cd backend && mvn spring-boot:run
cd backend && mvn test
cd backend && mvn clean package
```

前端：

```bash
cd frontend
pnpm install
pnpm dev
pnpm typecheck
pnpm build
```

## 项目架构

后端 Maven 项目位于 `backend`，Java 根包为 `com.luky.nexusmind`。

后端主要模块：

- `client`：AI 模型及外部服务客户端。
- `config`：Spring、安全、Redis、Kafka、MinIO、Elasticsearch 和 WebSocket 配置。
- `consumer`：异步文件处理消费者。
- `controller`：REST 接口。
- `model` / `entity`：持久化模型、搜索模型和消息模型。
- `repository`：数据访问层。
- `service`：业务逻辑。
- `handler`：WebSocket 聊天处理。
- `utils`：公共工具。

前端应用位于 `frontend/src`，使用 Vue 3、TypeScript、Vite、Naive UI、Pinia、Vue Router、UnoCSS 和 SCSS。

`homepage/` 为独立的静态产品介绍页，不是 Vue 主应用。

## 开发约定

- 除非任务明确要求改变功能，否则保持 API 行为兼容。
- 密钥等敏感信息通过环境变量提供，不得作为 YAML 默认值提交。
- 面向用户的产品名称统一使用 `NexusMind` 或 `知枢 NexusMind`。
- 不得重新引入原项目的作者链接、推广文案或品牌信息。

## 内存受限服务器上的 Docker 部署与验证

- 本节规则仅适用于在可用内存不足、且可能承载在线服务的服务器上进行 Docker 部署、部署验证或相关集成测试。普通本地开发、类型检查、构建和浏览器验证不受本节的串行与堆上限要求约束；本地环境出现明显内存压力时再按实际情况采用。
- 在上述场景中，测试、类型检查、构建和浏览器验证必须串行执行；上一项结束并释放资源后，才能启动下一项。禁止同时运行 Maven、前端构建、类型检查等重型验证。集成测试所需的依赖服务只能在该项验证期间启动，并纳入同一内存预算。
- 启动验证前先检查 `free -m` 和相关进程、容器的内存占用，根据当前可用内存制定预算，并为在线服务和操作系统保留余量；不能仅按服务器总内存估算。
- 为 Node 设置合理的 `NODE_OPTIONS=--max-old-space-size=...`，为 Maven 和 JVM 设置堆上限；测试容器必须设置总内存、交换内存和 CPU 限额。多个测试进程及依赖服务的总预算必须一起计算，禁止无上限启动重型验证。
- 优先运行与修改相关的测试。出现内存不足或持续交换时，停止本次验证并降低资源占用，不得直接提高并发或反复无上限重试。资源不足时如实报告未完成的检查。
- 验证结束及时停止并清理本次启动的临时进程、容器和浏览器；不得为了测试停止在线业务服务。不得将服务器重启作为测试或构建的恢复手段。
