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
- 岗位总数：9,435（2026-10-22 查重导入后）
- 向量不在 PostgreSQL 中存储
- 向量写入 Qdrant collection：`careernav_jobs`
- 向量维度：256
- 向量模型：字符 n-gram TF-IDF + TruncatedSVD

模型文件：

```text
D:\何处是家乡\data\postgresql\models\tfidf_svd_256.joblib
```
## 导入合并岗位数据

数据文件：`data/imports/careernav_jobs_combined.csv`

```powershell
# 只查重，不写库
python .\ops\import_combined_jobs.py --dry-run

# 正式导入（只新增，不覆盖、不删除）
python .\ops\import_combined_jobs.py
```

导入时会按 `external_id`、`content_hash` 和规范化后的“岗位名 + 公司 + 城市”查重。
导入报告写入：`output/combined_jobs_import_report.json`。

## 城市名称归一化

导入的 Greenhouse 等数据可能包含英文城市，可执行：

```powershell
python .\ops\translate_job_cities.py
```

脚本会把 `Beijing`、`Shanghai`、`Hong Kong`、`Taipei`、`Hsinchu` 等统一为中文，
同时修复多城市写法和 `location/search_text`。转化报告写入：
`output/city_translation_report.json`。
