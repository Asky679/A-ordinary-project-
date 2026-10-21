# Qdrant Vector DB

本地单机部署，官方 Windows 二进制，无 Docker、无外部依赖，遥测已关闭。

```text
版本      : 1.19.2
HTTP      : http://127.0.0.1:6333
gRPC      : 127.0.0.1:6334
数据目录  : D:\何处是家乡\new pj\tools\qdrant\storage
启动      : .\qdrant.exe --disable-telemetry
```

## 集合

| 集合 | 向量数 | 维度 | 距离 | 用途 |
| --- | ---: | ---: | --- | --- |
| `careernav_jobs` | 4,110 | 256 | Cosine | 岗位语义检索 |
| `careernav_interview_questions` | 1,508 | 256 | Cosine | 面试题库检索 |

两个集合都是 `on_disk_payload: true`。`indexed_vectors_count` 为 0 是正常现象：
向量数低于默认 `indexing_threshold`（10,000），Qdrant 不建 HNSW 索引，走精确暴力检索，
结果无近似误差，延迟仍在毫秒级。

## Payload 索引

```text
careernav_jobs                : status(keyword), dataset_year(integer), city(keyword)
careernav_interview_questions : source, stage, role, kind, lang, difficulty, skills (均为 keyword)
```

## 过滤必须下推

**原则：所有硬性筛选条件都要放进 Qdrant 的 `filter`，不要捞回 Java 再筛。**

反例（本项目已经改掉）：`JobVectorSearchService` 早期只把 `status` / `dataset_year` 下推，
城市是在 Java 里对召回结果做 `contains` 后置过滤，为了补偿被筛掉的行还额外捞了 4 倍数据。
实测代价很大——请求「长沙 · limit 20」时，语义 Top-200 里只有 6 条长沙岗位，
于是只返回 6 条，而库里其实有 26 条。

现在 `buildFilter(cityFilter, yearFilter)` 把城市也下推，直接按 `limit` 取数：

| 城市 | 旧写法（Top-200 后置过滤） | 现在（过滤下推） |
| --- | ---: | ---: |
| 长沙 | 6 | 20 |
| 深圳 | 10 | 20 |
| 广州 | 7 | 20 |
| 郑州 | 5 | 20 |
| 上海 | 6 | 20 |

附带收益：删掉了按岗位逐个查询 `dataset_year` 的 N+1 SQL（原 `datasetYear()` 方法）。

## 维护

集合碎片过多时（每个 segment 会预分配 32MB 文件）可以合并：

```text
PUT /collections/{collection}
{"optimizers_config": {"default_segment_number": 2}}
```

当前存储约 1.7 GB，其中约 1.15 GB 是 36 个 32MB 预分配文件（8 segments × 2 文件 × 2 集合），
实际数据只有约 10 MB。
