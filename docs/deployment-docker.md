# Docker 一键部署

## 目标

一条 Compose 编排以下服务：

- PostgreSQL 18：业务数据、账号、LangGraph 状态
- Qdrant：岗位向量与面试题向量
- 岗位 Embedding：5001
- 面试题 Embedding：5002
- LangGraph / FastAPI 面试与诊断 Agent：8001
- Spring Boot 主应用：8080

启动顺序由健康检查和一次性初始化容器控制：

1. PostgreSQL 健康
2. PostgreSQL 种子恢复（已有数据则跳过）
3. Qdrant 启动并恢复 collection snapshot（已存在 collection 则跳过）
4. 两个 Embedding 服务健康
5. 面试 Agent 健康
6. 主应用启动

## 1. 准备配置

复制环境变量模板并修改密码：

```bash
cp .env.docker.example .env
```

生产环境必须替换：

- `POSTGRES_PASSWORD`
- `APP_AUTH_SECRET`
- 如需在线大模型，填写 `OPENAI_API_KEY`

如果数据库密码包含 `@ : / # ?` 等 URL 保留字符，LangGraph 的
`CAREERNAV_DB_URL` 需要做 URL 编码，或改用只含字母、数字和短横线的长密码。

## 2. 准备模型

将两个模型放到：

```text
data/models/tfidf_svd_256.joblib
data/models/tfidf_svd_256_interview.joblib
```

模型不进公共仓库，需要单独上传。

## 3. 准备数据库与向量种子

将 `careernav-database-package-*.zip` 上传到服务器后执行：

```bash
./ops/docker/prepare-seed.sh output/careernav-database-package-2026-11-25.zip
```

Windows：

```powershell
powershell -ExecutionPolicy Bypass -File .\ops\docker\prepare-seed.ps1 `
  -Archive .\output\careernav-database-package-2026-11-25.zip
```

脚本只会替换 `docker/seed/postgres` 和 `docker/seed/qdrant` 下的固定文件：

```text
docker/seed/postgres/careernav.dump
docker/seed/qdrant/careernav_jobs-*.snapshot
docker/seed/qdrant/careernav_interview_questions-*.snapshot
```

没有种子文件也能启动，但岗位库和题库会为空。

## 4. 启动

```bash
docker compose up -d --build
```

后续只启动已有镜像：

```bash
docker compose up -d
```

查看状态：

```bash
docker compose ps
docker compose logs -f app
```

访问：

```text
http://服务器地址:8080
```

第一个注册账号自动成为管理员。

## 5. 健康检查

```text
主应用         GET /api/health
面试 Agent     GET http://interview-agent:8001/health（容器内）
岗位 Embedding GET http://embedding-jobs:5001/health（容器内）
面试 Embedding GET http://embedding-interview:5002/health（容器内）
PostgreSQL     pg_isready
Qdrant         GET /healthz
```

容器日志启用轮转，每个服务最多保留 5 个 20MB 文件。主应用另写入：

```text
logs/app/careernav.log
```

## 6. PostgreSQL 备份

```bash
./ops/docker/backup-postgres.sh
```

Windows 可执行：

```powershell
.\ops\docker\backup-postgres.ps1
```

备份输出到 `backups/postgres-时间.dump`。

建议用定时任务每天执行，并将 `backups` 同步到另一台机器或对象存储。不要只把备份放在同一台服务器。

## 7. PostgreSQL 恢复

恢复会覆盖当前业务数据：

```bash
./ops/docker/restore-postgres.sh backups/postgres-20261125T120000Z.dump
```

Windows 可执行：

```powershell
.\ops\docker\restore-postgres.ps1 -Backup .\backups\postgres-20261125T120000Z.dump
```

脚本会先停止主应用和面试 Agent，恢复完成后重新启动。无人值守恢复可设置：

```bash
FORCE=1 ./ops/docker/restore-postgres.sh backups/postgres-example.dump
```

## 8. 演示数据分区

管理员登录后：

1. 点击顶部“演示数据管理”
2. 创建演示账号
3. 首次创建时，旧的未归属测试数据会自动归入 DEMO 账号
4. 用演示账号登录时，只看到演示账号自己的数据
5. 真实学生账号互相隔离，且看不到 DEMO 数据
6. 管理员默认隐藏 DEMO 数据，可点击“显示演示数据”切换
7. 管理员可一键清空全部 DEMO 数据，不影响真实账号

数据标记：

- `REAL`：真实学生数据
- `DEMO`：答辩、测试和演示数据
- `LEGACY`：旧版本中尚未归属账号的数据

## 9. 常用维护命令

```bash
# 查看所有服务状态
docker compose ps

# 查看主应用日志
docker compose logs -f app

# 只重启主应用
docker compose restart app

# 停止但不删除数据卷
docker compose down

# 停止并删除数据卷（会清空数据库与向量库，谨慎）
docker compose down -v
```
