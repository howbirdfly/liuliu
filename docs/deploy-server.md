# LiuLiu 旧服务器部署说明

这份说明按你当前项目的真实结构整理，默认采用这条线上策略：

- 前端：`Vite build` 后交给 `Nginx`
- 后端：`Spring Boot jar + systemd`
- 数据：`MySQL + Redis`
- AI：保留 `Agent + DeepSeek + 地图`
- RAG：线上先关闭 `Milvus / embedding / rag`

## 1. 推荐部署方式

推荐在本地电脑完成构建，再把产物传到服务器。

原因：

- 你现在的服务器是 `2 核 4G`，本地构建更稳
- 线上只跑运行时，不承担 `npm build + maven package` 压力
- 也更适合后续快速回滚

## 2. 服务器上最终目录

建议统一放在 `/opt/liuliu`：

```text
/opt/liuliu
├─ app/                     # 后端 jar
├─ config/                  # 外置 application-prod.yml
├─ logs/                    # Spring Boot 日志
└─ www/                     # 前端 dist 内容
```

先执行：

```bash
sudo mkdir -p /opt/liuliu/app /opt/liuliu/config /opt/liuliu/logs /opt/liuliu/www
sudo chown -R $USER:$USER /opt/liuliu
```

## 3. 本地构建

### 前端

在项目根目录新建 `.env.production`：

```env
VITE_API_BASE_URL="https://liu--liu.com"
VITE_USE_MOCK_LOGIN="false"
VITE_AMAP_JS_KEY="你的高德 JS Key"
```

然后构建：

```bash
npm install
npm run build
```

### 后端

```bash
cd backend
mvn -DskipTests package
```

产物是：

- 前端：`dist/`
- 后端：`backend/target/citywalk-backend-0.0.1-SNAPSHOT.jar`

## 4. 上传到服务器

下面给你一套最直接的上传方式，按你自己的服务器 IP 改：

```bash
scp -r dist/* root@YOUR_SERVER_IP:/opt/liuliu/www/
scp backend/target/citywalk-backend-0.0.1-SNAPSHOT.jar root@YOUR_SERVER_IP:/opt/liuliu/app/
scp backend/src/main/resources/application-prod.yml root@YOUR_SERVER_IP:/opt/liuliu/config/
```

如果你不想用 `root`，换成你的普通用户也可以。

## 5. 准备生产配置

仓库里的 [application-prod.yml](/d:/liuliu/liuliu/backend/src/main/resources/application-prod.yml) 我已经改成了生产模板，但你上线前必须把真实值填进去。

至少要改这些：

- `MYSQL_URL`
- `MYSQL_USERNAME`
- `MYSQL_PASSWORD`
- `REDIS_HOST`
- `REDIS_PASSWORD`
- `AMAP_WEB_KEY`
- `DEEPSEEK_API_KEY`
- `OSS_ACCESS_KEY_ID`
- `OSS_ACCESS_KEY_SECRET`
- `OSS_BUCKET_NAME`
- `JWT_SECRET`
- `MAIL_USERNAME`
- `MAIL_PASSWORD`

当前线上默认就是：

- `liuliu.rag.enabled=false`
- `milvus.enabled=false`
- `embedding.enabled=false`

这正好符合你现在的部署策略。

## 6. 初始化数据库

如果旧服务器数据库已经有之前的数据，先不要重复导入全量 SQL。

如果是首次部署，建议顺序：

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS liuliu_citywalk DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -u root -p liuliu_citywalk < "backend/liuliu (2).sql"
mysql -u root -p liuliu_citywalk < backend/sql/2026-05-14-upgrade-existing-db.sql
mysql -u root -p liuliu_citywalk < backend/sql/2026-05-14-upgrade-community-interactions.sql
mysql -u root -p liuliu_citywalk < backend/sql/2026-05-19-add-notifications.sql
```

如果服务器上没有项目源码，只上传了构建产物，那就先把这些 SQL 文件也传上去。

注意：

- 如果旧库已经存在表，执行前先备份
- `co_create_rooms` 和 `co_create_room_members` 也要确认已经存在
- 部署前最好先在 MySQL 里 `SHOW TABLES;` 看一眼

## 7. 安装运行环境

如果旧服务器已经有这些服务，可以跳过。

Ubuntu 常用命令：

```bash
sudo apt update
sudo apt install -y openjdk-21-jre-headless nginx redis-server
```

校验版本：

```bash
java -version
nginx -v
redis-cli ping
```

`java -version` 需要看到 `21`。

## 8. 配 systemd 启动后端

新建 `/etc/systemd/system/liuliu.service`：

```ini
[Unit]
Description=LiuLiu CityWalk Backend
After=network.target

[Service]
User=root
WorkingDirectory=/opt/liuliu/app
ExecStart=/usr/bin/java -jar /opt/liuliu/app/citywalk-backend-0.0.1-SNAPSHOT.jar --spring.config.location=file:/opt/liuliu/config/application-prod.yml
SuccessExitStatus=143
Restart=always
RestartSec=5
StandardOutput=append:/opt/liuliu/logs/backend.log
StandardError=append:/opt/liuliu/logs/backend.log

[Install]
WantedBy=multi-user.target
```

然后执行：

```bash
sudo systemctl daemon-reload
sudo systemctl enable liuliu
sudo systemctl start liuliu
sudo systemctl status liuliu
```

看日志：

```bash
tail -f /opt/liuliu/logs/backend.log
```

## 9. 配 Nginx

新建 `/etc/nginx/sites-available/liuliu.conf`：

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    '' close;
}

server {
    listen 80;
    server_name liu--liu.com www.liu--liu.com;

    root /opt/liuliu/www;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }

    location /ws/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600s;
    }
}
```

启用配置：

```bash
sudo ln -s /etc/nginx/sites-available/liuliu.conf /etc/nginx/sites-enabled/liuliu.conf
sudo nginx -t
sudo systemctl reload nginx
```

这里有两个关键点：

- `/api/` 关闭 `proxy_buffering`，否则 `Agent SSE` 和通知流容易卡住
- `/ws/` 必须带 `Upgrade / Connection` 头，否则房间实时同步会失效

## 10. HTTPS

如果域名已经解析到服务器，推荐直接用 `certbot`：

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d liu--liu.com -d www.liu--liu.com
```

前端 `.env.production` 里的 `VITE_API_BASE_URL` 要和最终访问域名一致，建议直接写：

```env
VITE_API_BASE_URL="https://liu--liu.com"
```

这样：

- 普通 API 走 `https://liu--liu.com/api/...`
- Agent SSE 走 `https://liu--liu.com/api/v1/agent/stream`
- 通知 SSE 走 `https://liu--liu.com/api/v1/notifications/stream`
- WebSocket 自动走 `wss://liu--liu.com/ws/co-create`

## 11. 上线后自检

先看后端是否启动：

```bash
curl http://127.0.0.1:8080/api/v1/community/walks
```

再看 Nginx 代理是否通：

```bash
curl https://liu--liu.com/api/v1/community/walks
```

上线后重点检查这几项：

- 首页是否正常打开
- 登录 / 注册 / 邮件验证码是否正常
- 地图检索是否正常
- Agent 对话是否正常返回
- Agent 流式输出是否能持续刷新
- 房间多人实时同步是否正常
- 单人刷新后是否能恢复
- 社区发帖和图片上传是否正常
- 通知流是否正常

## 12. 你这次部署最短路径

如果你想最快上线，就按这个顺序走：

1. 本地补好 `.env.production`
2. 本地执行 `npm run build`
3. 本地执行 `cd backend && mvn -DskipTests package`
4. 把 `dist/`、jar、`application-prod.yml` 传到服务器
5. 在服务器填好真实生产配置
6. 启动 `liuliu.service`
7. 配 Nginx + HTTPS
8. 做一轮登录、Agent、房间、单人恢复自测

## 13. 上线前安全提醒

这轮对话里你之前暴露过真实：

- JWT secret
- embedding key
- 其他 API key

上线前请先轮换，不要继续使用旧值。
