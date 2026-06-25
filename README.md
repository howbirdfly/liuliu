# LiuLiu City Walk

一个围绕 City Walk 场景搭建的前后端一体项目，包含主题生成、路线探索、轨迹记录、社区分享、共创房间和 Agent 路线规划等能力。

## 项目结构

```text
.
├─ src/                              # React + Vite 前端
│  ├─ components/                    # 页面组件与业务组件
│  ├─ services/                      # 前端 API 封装
│  ├─ App.tsx                        # 前端主页面
│  ├─ main.tsx                       # 前端入口
│  └─ index.css                      # 全局样式
├─ backend/                          # Spring Boot 后端
│  ├─ src/main/java/com/liuliu/citywalk
│  │  ├─ controller/                 # REST / SSE 接口
│  │  ├─ service/                    # 业务服务与 Agent 流程
│  │  ├─ service/agent/              # LLM 客户端、消息模型、工具协议
│  │  ├─ service/agent/tool/         # Agent 可调用工具
│  │  ├─ service/rag/                # 向量检索、知识库摄取、召回
│  │  ├─ mapper/                     # MyBatis Mapper
│  │  ├─ mapper/entity/              # 数据库实体
│  │  ├─ model/dto/                  # 请求响应 DTO
│  │  ├─ config/                     # 配置类
│  │  ├─ interceptor/                # 登录态拦截器
│  │  ├─ websocket/                  # 共创房间实时通信
│  │  └─ CityWalkBackendApplication  # 后端启动入口
│  ├─ src/main/resources/
│  │  ├─ application-local.yml       # 本地开发配置
│  │  └─ application-prod.yml        # 生产配置模板
│  ├─ liuliu (2).sql                 # 数据库初始化脚本
│  └─ LiuLiu.sql                     # 另一份数据库初始化脚本
├─ docs/                             # 部署、设计和补充文档
├─ scripts/                          # 辅助脚本
├─ docker-compose.milvus.yml         # 本地启动 Milvus 的示例编排
├─ package.json                      # 前端依赖与脚本
└─ README.md
```

## 核心模块说明

### 前端

- `src/App.tsx`
  当前主交互页面，承接主题选择、地图、轨迹、社区、Agent、房间等主要 UI。
- `src/services/`
  按能力拆分 API 调用，例如：
  - `authApi.ts`：登录、注册、用户信息
  - `agentApi.ts`：Agent 同步对话和流式规划
  - `walkApi.ts`：漫步记录、轨迹点、详情
  - `communityApi.ts`：社区动态、评论、点赞、收藏
  - `roomApi.ts`：共创房间
  - `mapApi.ts`：地图搜索与 POI
  - `fileApi.ts`：文件上传

### 后端

- `controller/`
  暴露所有 HTTP 接口，按业务划分为认证、主题、Walk、社区、房间、Agent、RAG、文件上传等。
- `service/`
  业务编排层，处理主题生成、路线规划、通知、房间状态、用户会话等核心逻辑。
- `service/agent/`
  封装大模型调用协议，包括消息结构、工具调用定义和 DeepSeek 客户端。
- `service/agent/tool/`
  提供给 Agent 的外部工具，例如知识库检索、POI 搜索、社区路线检索、Walk 详情读取等。
- `service/rag/`
  负责知识库导入、Embedding、Milvus 向量检索、召回与重排。
- `mapper/`
  基于 MyBatis 操作 MySQL。
- `websocket/`
  处理共创房间的实时消息，默认入口为 `/ws/co-create`。

## 技术栈

### 前端

- React 19
- TypeScript
- Vite
- Lucide React
- Firebase SDK

### 后端

- Spring Boot 3.4.4
- Spring Web / Validation / WebSocket / Actuator / Mail / Redis
- Spring AI
- MyBatis
- MySQL 8

### AI 与外部能力

- DeepSeek：对话和 Agent 规划
- 高德地图：POI 搜索、地点上下文
- 阿里云 OSS：图片上传
- Milvus：向量知识库

## 主要接口入口

后端接口主要集中在以下几组路径：

- `/api/v1/auth`：登录、注册、验证码、个人信息
- `/api/v1/themes`：主题数据
- `/api/v1/ai`：AI 主题生成、流式生成、任务校验
- `/api/v1/walks`：漫步记录、会话、详情
- `/api/v1/community`：社区动态、评论、互动
- `/api/v1/co-create`：共创房间
- `/api/v1/agent`：Agent 对话、流式规划、记忆清理
- `/api/v1/map`：地图搜索与附近 POI
- `/api/v1/rag`：知识库检索与摄取
- `/api/v1/files`：文件上传
- `/api/v1/notifications`：通知与 SSE 流

## 本地启动前准备

建议先准备这些基础环境：

- Node.js 18 或更高版本
- npm 9 或更高版本
- Java 21
- Maven 3.9+
- MySQL 8

可选依赖：

- Redis：用于部分缓存、通知、会话和房间相关能力
- Docker：如果要跑 Milvus / RAG，本地会更方便

## 前端启动

### 1. 安装依赖

```bash
npm install
```

### 2. 配置环境变量

项目根目录可参考 `.env.example` 新建或修改 `.env.local`：

```env
VITE_API_BASE_URL="http://localhost:8080"
VITE_USE_MOCK_LOGIN="false"
VITE_AMAP_JS_KEY="YOUR_AMAP_JS_KEY"
```

说明：

- `VITE_API_BASE_URL`：后端服务地址
- `VITE_USE_MOCK_LOGIN`：是否启用前端的 mock 登录流程
- `VITE_AMAP_JS_KEY`：高德 JS API Key

### 3. 启动前端开发服务

```bash
npm run dev
```

默认访问地址：

```text
http://localhost:3000
```

## 后端启动

### 1. 初始化数据库

任选一份 SQL 脚本导入本地 MySQL：

```bash
mysql -uroot -p liuliu_citywalk < "backend/liuliu (2).sql"
```

或：

```bash
mysql -uroot -p liuliu_citywalk < "backend/LiuLiu.sql"
```

### 2. 检查本地配置

后端本地配置文件在：

- `backend/src/main/resources/application-local.yml`

启动前至少要确认这些配置可用：

- `spring.datasource`：MySQL 地址、账号、密码
- `amap`：高德地图 Key
- `deepseek`：大模型地址与 Key
- `jwt`：登录态签名配置

按需确认：

- `spring.mail`：邮箱验证码功能
- `sky.alioss`：图片上传功能
- `spring.data.redis`：Redis 相关能力
- `milvus` 与 `embedding`：RAG / 知识库能力

### 3. 启动 Spring Boot

进入后端目录：

```bash
cd backend
```

使用本地 profile 启动：

```bash
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

如果你的 Maven 不在环境变量里，也可以直接指定完整路径，例如：

```bash
D:\Maven\apache-maven-3.9.11\bin\mvn.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

默认后端端口：

```text
http://localhost:8080
```

### 4. 打包后运行

```bash
cd backend
mvn -DskipTests package
java -jar target/citywalk-backend-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

## 可选能力启动说明

### Redis

如果你要测试通知、缓存、会话或部分实时能力，建议准备 Redis，并确保 `application-local.yml` 中的 Redis 地址可访问。

### Milvus / RAG

如果你要测试知识库检索链路，需要额外准备 Milvus 和 Embedding 配置。

仓库里已经提供了编排文件：

```bash
docker compose -f docker-compose.milvus.yml up -d
```

如果你暂时不想启用知识库，可以把本地配置中的相关开关关闭，或使用不依赖 RAG 的功能先联调前后端。

## 推荐启动顺序

```text
1. 启动 MySQL
2. 导入 SQL
3. 检查 backend/application-local.yml
4. 启动后端（8080）
5. 配置前端 .env.local
6. 启动前端（3000）
7. 打开浏览器联调
```

## 常用命令

前端开发：

```bash
npm run dev
```

前端类型检查：

```bash
npm run lint
```

前端打包：

```bash
npm run build
```

后端开发启动：

```bash
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

后端打包：

```bash
cd backend
mvn -DskipTests package
```

## 部署相关文档

如果你后面要继续整理上线流程，可以看 `docs/` 目录里的资料：

- `docs/deploy-server.md`
- `docs/seed-community-walks.md`
- `docs/springboot-api-plan.md`

其中部分文档可能更偏阶段性记录，建议以当前代码和配置文件为准。
## Docker 整仓启动

仓库现在已经补了一份整仓 `docker-compose.yml`，可以把前端、后端、MySQL、Redis 和 Milvus 一起拉起来。

### 1. 准备环境变量

先复制一份 Docker 环境模板：

```bash
cp .env.docker.example .env
```

Windows PowerShell 可以用：

```powershell
Copy-Item .env.docker.example .env
```

至少建议检查这些字段：

- `DEEPSEEK_API_KEY`
- `AMAP_WEB_KEY`
- `VITE_AMAP_JS_KEY`
- `OSS_ACCESS_KEY_ID`
- `OSS_ACCESS_KEY_SECRET`
- `OSS_BUCKET_NAME`

如果你只是先把整仓跑起来，不立刻测试 AI、地图或上传，也可以先留空。

### 2. 一条命令启动

在项目根目录执行：

```bash
docker compose up -d --build
```

### 3. 默认访问地址

- 前端：http://localhost:3000
- 后端：http://localhost:8080
- MySQL：localhost:3306
- Redis：localhost:6379
- Milvus：localhost:19530
- MinIO Console：http://localhost:9001

### 4. 停止

```bash
docker compose down
```

如果连数据卷一起删：

```bash
docker compose down -v
```

### 5. 说明

- 前端容器通过 Nginx 反代 `/api`、`/ws` 和 `/uploads` 到后端，所以 SSE 和 WebSocket 可以直接走同域。
- 后端容器默认使用 `prod` profile，并通过 Compose 环境变量连接 MySQL、Redis 和 Milvus。
- MySQL 首次启动时会自动执行 `backend/LiuLiu.sql` 和几个增量 SQL 脚本完成初始化。
