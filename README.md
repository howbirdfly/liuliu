# LiuLiu City Walk

一个结合地图、AI 主题生成、轨迹记录、社区分享和多人共创房间的 City Walk Web 项目。

## 项目结构

```text
.
├─ src/                         # React + Vite 前端
├─ backend/                     # Spring Boot 后端
│  ├─ src/main/java/...         # 控制器、服务、仓储
│  ├─ src/main/resources/       # 本地配置
│  ├─ liuliu (2).sql            # 现有数据库初始化脚本
│  └─ LiuLiu.sql
├─ docs/                        # 设计或补充资料
└─ README.md
```

## 当前能力

- AI 生成个人漫步主题、组合主题和任务
- 高德地图定位、选点、轨迹记录和历史轨迹详情
- QQ 邮箱注册 / 登录
- 图片上传到阿里云 OSS
- 社区公开记录浏览
- 个人主页、详细记录卡、任务打卡
- 进阶模式下的共创房间
  - 房主创建房间号
  - 其他成员输入房间号加入
  - 最多 5 人
  - 所有成员的位置点和轨迹同时显示在地图上

## 技术栈

### 前端

- React 19
- TypeScript
- Vite
- Lucide React
- 高德 JS API

### 后端

- Spring Boot 3.4.4
- Spring JDBC
- MySQL 8
- JavaMail
- 阿里云 OSS SDK

## 本地开发

### 1. 前端

安装依赖：

```bash
npm install
```

创建或检查本地环境变量，例如 `.env.local`：

```env
VITE_API_BASE_URL="http://localhost:8080"
VITE_USE_MOCK_LOGIN="false"
VITE_AMAP_JS_KEY="YOUR_AMAP_JS_KEY"
```

启动前端：

```bash
npm run dev
```

默认地址：

```text
http://localhost:3000
```

### 2. 后端

进入后端目录：

```bash
cd backend
```

启动方式二选一：

```bash
mvn spring-boot:run
```

或

```bash
mvn -DskipTests package
java -jar target/citywalk-backend-0.0.1-SNAPSHOT.jar
```

## 数据库初始化

### 基础表

先导入已有 SQL：

```bash
mysql -uroot -p liuliu_citywalk < "backend/liuliu (2).sql"
```

或根据你实际使用的脚本导入：

```bash
mysql -uroot -p liuliu_citywalk < "backend/LiuLiu.sql"
```

### 房间共创表

由于项目已经移除了“后端启动时自动建表”逻辑，所以新增的房间表需要手动创建。

执行下面这段 SQL：

```sql
CREATE TABLE IF NOT EXISTS co_create_rooms (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_code VARCHAR(16) NOT NULL UNIQUE,
  owner_user_id BIGINT NOT NULL,
  theme_snapshot TEXT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'active',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_co_create_rooms_owner_user_id (owner_user_id)
);

CREATE TABLE IF NOT EXISTS co_create_room_members (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  nickname VARCHAR(64) NOT NULL,
  avatar_url VARCHAR(512) NULL,
  track_color VARCHAR(32) NOT NULL,
  route_points TEXT NULL,
  current_position TEXT NULL,
  completed_missions TEXT NULL,
  is_tracking TINYINT(1) NOT NULL DEFAULT 0,
  last_active_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_co_create_room_member (room_id, user_id),
  INDEX idx_co_create_room_members_room_id (room_id),
  INDEX idx_co_create_room_members_user_id (user_id)
);
```

## 配置说明

### 前端环境变量

常用项：

```env
VITE_API_BASE_URL="http://localhost:8080"
VITE_USE_MOCK_LOGIN="false"
VITE_AMAP_JS_KEY="YOUR_AMAP_JS_KEY"
```

### 后端配置

后端生产环境通常通过外部配置文件启动，例如：

```bash
java -jar target/citywalk-backend-0.0.1-SNAPSHOT.jar \
  --spring.config.location=file:/opt/liuliu/config/application-prod.yml
```

生产配置需要至少包含：

- `spring.datasource`
- `spring.mail`
- `amap`
- `deepseek`
- `mission-verify-ai`
- `sky.alioss`
- `jwt`

## 图片上传说明

项目当前是：

- 前端先请求后端获取 OSS 直传签名
- 浏览器直接上传到 OSS
- 后端只负责签名和落库

这意味着你需要在 OSS Bucket 上配置：

- 公共读
- CORS 放行以下来源

本地开发：

- `http://localhost:3000`
- `http://127.0.0.1:3000`

线上域名：

- `https://liu--liu.com`
- `https://www.liu--liu.com`

推荐允许的方法：

- `POST`
- `GET`
- `OPTIONS`

## 生产部署

### 前端

构建前端：

```bash
npm run build
```

通常使用 Nginx 托管 `dist/`。

### 后端

构建后端：

```bash
cd backend
mvn -DskipTests package
```

启动：

```bash
java -jar target/citywalk-backend-0.0.1-SNAPSHOT.jar \
  --spring.config.location=file:/opt/liuliu/config/application-prod.yml
```

### Nginx 反向代理示例

```nginx
server {
    listen 80;
    server_name liu--liu.com www.liu--liu.com;

    root /opt/liuliu/liuliu/dist;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

## 开发提示

- 纯净模式保持单人漫步体验
- 进阶模式是房间共创模式
- 项目已经移除了 Gemini 相关后端代码，当前主用 AI 侧为 DeepSeek / 其他现有配置
- 项目已经移除了后端启动自动建表，请把数据库迁移和建表放到 SQL 或迁移脚本中管理

## 常用命令

前端类型检查：

```bash
npm run lint
```

前端构建：

```bash
npm run build
```

后端构建：

```bash
cd backend
mvn -DskipTests package
```
