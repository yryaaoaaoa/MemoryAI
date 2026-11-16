# GitHub Actions 自动部署验证（2026-11-16）

当前项目代码和部署流程已推送到 `yryaaoaaoa/MemoryAI` 的 `master` 分支。首次成功自动发布的应用提交为 `cf1042ba9be71621c764539ce3e232c82883920b`。

- [成功运行记录](https://github.com/yryaaoaaoa/MemoryAI/actions/runs/43822833126)
- [工作流与手动发布入口](https://github.com/yryaaoaaoa/MemoryAI/actions/workflows/deploy.yml)
- [部署与运维说明](../deploy/docker/README.md)

## 发布行为

修改前后端或部署相关文件并推送 `master` 后自动触发。工作流执行 Maven 测试与打包、前端单元测试与构建、部署脚本测试，再通过 SSH 和 rsync 增量传输，校验完整 tar 包的 SHA256，构建镜像并更新前后端容器。也支持在 GitHub 网页手动触发 `master` 的发布。

服务器使用专用账号 `memoryai-deploy` 和独立 SSH 密钥。业务凭据仍在服务器 `.env`，GitHub 只保存部署所需的 Secrets。Secrets、私钥、构建产物以及无关的本地 `outputs` 文件未提交到仓库。

## 验证结果

- GitHub Actions 本轮所有步骤成功，包含后端、前端和 5 项部署脚本测试。
- 原始发布包为 121,477,120 字节，rsync 复用了 118,510,128 字节，实际发送 1,731,679 字节（约 1.65 MiB）。这是本次代码和依赖变化下的实测结果，后续传输量取决于改动。
- Nginx 配置检查通过，前后端容器均 healthy。
- 公网首页返回 HTTP 200，公共知识库 API 返回业务成功。
- 发布前后对比 MySQL、Redis、Elasticsearch 和两个 QQ 服务容器的 ID 及启动时间，全部未变。
- 当前版本指针为 `/opt/memoryai/releases/cf1042ba9be71621c764539ce3e232c82883920b-43822833126-1`，上一版本保留在 `releases/bootstrap`。

构建失败保持原服务不变；容器启动或 Nginx/API 检查失败时恢复上一版应用。回退分支通过模拟 Docker 的测试验证，本次真实发布成功，未人为触发线上故障或回退。

## 保留边界

数据库结构迁移和 MySQL、Redis、ES 配置升级不在自动应用发布步骤中，需单独安排。发布可能短暂中断，当前未实现零停机切换。业务访问仍为 HTTP IP 地址。

首次整包上传因跨境传输缓慢被主动取消，后改用增量传输并完成上述成功发布；取消时尚未替换线上容器。
