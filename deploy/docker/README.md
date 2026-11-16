# MemoryAI Docker 部署

部署包括 Nginx 前端、Java 后端、MySQL 8.0.41、Redis 7.4 和 Elasticsearch 8.17.10。Compose 项目名为 `memoryai`，与 Myblog 的容器、网络和数据卷分开。可以复用服务器已有基础镜像，不复用博客数据。

## 日常更新：GitHub Actions

推送 `master` 分支中的前后端代码或部署配置，会触发 [Deploy MemoryAI](https://github.com/yryaaoaaoa/MemoryAI/actions/workflows/deploy.yml)。也可以在该页面选择 **Run workflow** 手动发布 `master`。仅修改文档不会自动发布。

工作流运行后端测试、前端测试和构建、部署脚本测试，随后通过 SSH 和 rsync 增量上传部署包，并校验完整包的 SHA256。发布包采用未压缩 tar，让 rsync 复用服务器上已有的 JAR 依赖字节，传输时再压缩变化部分。服务器需安装 rsync，并保留 `/opt/memoryai/incoming/release.tar` 作为下一次传输的比较基准。

服务器先构建新镜像，再更新前后端容器并检查 Nginx 与 API。发布不重建 MySQL、Redis 或 ES，不执行数据库迁移，不清除数据卷。应用启动或代理检查失败时恢复上一版应用，工作流仍显示失败。

部署使用独立账号 `memoryai-deploy`，账号具有 Docker 管理权限。仓库 Secrets 包含 `DEPLOY_HOST`、`DEPLOY_USER`、`DEPLOY_SSH_KEY`、`DEPLOY_KNOWN_HOSTS`。业务 API Key 和数据库密码保留在服务器 `/opt/memoryai/.env`，不上传 GitHub。

服务器保留 `/opt/memoryai/releases/版本号`，`current` 指向当前成功版本，`previous` 指向上一次成功版本。镜像使用提交 SHA、工作流运行编号和重试编号标记。查看当前部署：

```sh
bash /opt/memoryai/current/compose.sh ps
bash /opt/memoryai/current/compose.sh logs --tail=100 backend
```

需要手动恢复上一版本时，先检查 `readlink -f /opt/memoryai/previous`，再执行：

```sh
bash /opt/memoryai/previous/compose.sh up -d --no-deps --force-recreate --wait --wait-timeout 180 backend web
```

上述手动命令只恢复容器；确认恢复成功后再调整 `current` 和 `previous` 指针。自动回退会保留正确的当前版本指针。回退应用不会回退数据库结构；涉及表结构或基础服务配置的变更需要单独安排迁移和验证。

已有 Actions 部署的服务器不要再在 `/opt/memoryai` 根目录执行旧版 `docker compose up`，应使用 `current/compose.sh`，避免加载首次安装时的旧配置。旧版本暂不自动清理，后续可在确认不再需要回退后按版本清理。

## 本机构建

需要 JDK 17、Maven、Node.js 和 pnpm 10.10.0。先在前端目录安装锁定依赖，再从项目根目录运行：

```powershell
./deploy/docker/build.ps1
```

脚本运行后端测试和前端构建，将 JAR、静态资源、初始化脚本和部署配置打包到 `deploy/docker/memoryai-时间戳.tar.gz`。已完成同一份代码的构建验证时可用 `-SkipBuild`。压缩包和 Docker 构建上下文均不包含 `.env` 或本机 `application.yml`。

## 新服务器安装

将压缩包上传并解压到 `/opt/memoryai`，复制 `.env.example` 为 `.env`，填写三个独立随机密码，以及 DeepSeek、SiliconFlow 的 API Key。OCR 配置用于扫描 PDF/图片识别；不配置时不能依赖该路径完成识别。

```sh
cd /opt/memoryai
chmod 600 .env
docker compose config --quiet
docker compose build
docker compose up -d --wait --wait-timeout 240
docker compose ps -a
```

默认访问 `http://服务器IP/`，只有网页端口 80 映射到宿主机。数据库、Redis、ES 和后端不映射公网端口。HTTP 部署设置 `COOKIE_SECURE=false`；接入 HTTPS 后改为 `true` 并配置反向代理。当前配置不提供 TLS。

首次启动自动创建空 MySQL 数据库和 `knowledge_chunk` 索引，RAG 默认开启。索引使用 1024 维向量，对应 `BAAI/bge-m3`，更换嵌入模型时必须同步核对向量维度。已有索引会保留，初始化服务不会覆盖它。

启动后在网页自行注册账号。注册得到普通用户权限；本部署不创建默认管理员、不导入本机用户或业务数据。

## 更新与运维

```sh
cd /opt/memoryai
docker compose ps -a
docker compose logs --tail=100 backend
docker compose stop
docker compose up -d --wait --wait-timeout 240
```

`stop` 和不带 `-v` 的 `down` 保留数据卷。不要使用 `docker compose down -v`，它会删除数据库和 ES 数据。更新前备份数据与旧部署包，保留服务器 `.env`，上传新部署包后重新构建并启动。`schema.sql` 只在空数据卷首次启动时执行，后续数据库结构升级需按项目迁移文档执行，不能只替换 JAR。

容器内存上限约为 4.1 GiB，ES JVM 堆为 512 MiB，Java 后端最大堆为 768 MiB。实际可承载的用户量和文档量需通过运行观察确定。
