# PostgreSQL 业务数据库

PostgreSQL 只负责业务数据，不再负责向量存储。

- PostgreSQL 18.6
- 数据目录：`D:\何处是家乡\data\postgresql\pgdata`
- 数据库：`careernav`
- 用户：`careernav`
- 监听地址：`127.0.0.1:5432`
- 本地 Embedding API：`127.0.0.1:5001`
- 向量数据库：Qdrant，见 `ops/qdrant/README.md`

## 启动 PostgreSQL

```powershell
.\ops\postgres\start.ps1
```

## 启动本地向量服务

```powershell
python .\ops\postgres\embedding_server.py
```

## 停止 PostgreSQL

```powershell
.\ops\postgres\stop.ps1
```

## 岗位数据

- 表名：`jobs`
- 岗位总数：4,110
- 向量不在 PostgreSQL 中存储
- 向量写入 Qdrant collection：`careernav_jobs`
- 向量维度：256
- 向量模型：字符 n-gram TF-IDF + TruncatedSVD

模型文件：

```text
D:\何处是家乡\data\postgresql\models\tfidf_svd_256.joblib
```