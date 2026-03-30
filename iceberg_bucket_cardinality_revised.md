# Iceberg 버킷 설계 재검토 — 카디널리티 재조사 + 목표 파일 크기 256MB 기준

> 버킷 컬럼 카디널리티 재조사 결과와 목표 파일 크기 **256 MB** 기준을 반영하여
> 16 / 32 / 64 버킷 설계를 다시 분석한 문서입니다.

---

## 1. 확정된 설계 기준

| 항목 | 값 |
|---|---|
| 일 데이터량 | ~6억 건 / 1 TB |
| 파티션 구조 | `DT × 특정코드` (카디널리티 15) |
| 파티션당 평균 데이터 | 1 TB ÷ 15 ≈ 67 GB |
| **목표 파일 크기** | **256 MB** |
| 버킷 컬럼 카디널리티 (재조사) | 최대 277 / 87% 구간 ≤ 10 |
| 테스트 버킷 수 | 16 / 32 / 64 |

### 파일 크기 판정 기준 (256 MB 목표)

| 파일 크기 | 상태 | 이유 |
|---|---|---|
| < 64 MB | ❌ 소파일 | 메타데이터 오버헤드, planning latency 급증 |
| 64 MB ~ 128 MB | ⚠️ 허용 미만 | 기능은 하나 최적 미달, Compaction 권장 |
| **128 MB ~ 256 MB** | ✅ 권장 범위 | Parquet 효율 + 쿼리 성능 균형 |
| 256 MB ~ 512 MB | ⚠️ 초과 | 허용되나 목표 초과, Compaction 대상 |
| > 512 MB | ❌ 과대 | row group 메모리 압박, Spark OOM, pruning 효율 저하 |

---

## 2. 카디널리티가 버킷 효율에 미치는 원리

### 실효 버킷 수

```
실효 버킷 수 = min(설정 버킷 수 N, 카디널리티)
```

버킷 컬럼의 고유값(카디널리티)이 N보다 작으면 **실제 생성 파일 수는 카디널리티에 수렴**합니다.

```
카디널리티 = 10, bucket(64) → 실효 버킷 수 ≈ 10 → 파일 최대 10개
카디널리티 = 10, bucket(32) → 실효 버킷 수 ≈ 10 → 파일 최대 10개
카디널리티 = 10, bucket(16) → 실효 버킷 수 ≈ 10 → 파일 최대 10개

→ 87% 데이터에서는 버킷 수 16 / 32 / 64 모두 동일한 결과
```

---

## 3. 256 MB 목표 기준 — 카디널리티 구간별 분석

### 3-1. 저카디널리티 (87% 데이터, 카디널리티 ≤ 10)

버킷 수와 무관하게 파일 수는 카디널리티(≤10)에 수렴합니다.

| 파티션 데이터량 | 카디널리티 | 자연 생성 파일 수 | Write 후 파일 크기 | 256 MB 대비 | 조치 |
|---|---|---|---|---|---|
| 35 GB | 10 | ~10개 | 3,500 MB | ❌ 14배 초과 | Compaction 필수 (→ ~137개 파일) |
| 15 GB | 10 | ~10개 | 1,500 MB | ❌ 6배 초과 | Compaction 필수 (→ ~59개 파일) |
| 5 GB | 10 | ~10개 | 500 MB | ⚠️ 2배 초과 | Compaction 필수 (→ ~20개 파일) |
| 2.5 GB | 10 | ~10개 | 250 MB | ✅ 목표 범위 | 조치 불필요 |
| 1 GB | 5 | ~5개 | 200 MB | ✅ 목표 범위 | 조치 불필요 |
| 300 MB | 3 | ~3개 | 100 MB | ⚠️ 미달 | Compaction 권장 (병합) |

> **16 / 32 / 64 버킷 수를 아무리 올려도 이 구간의 파일 크기는 바뀌지 않습니다.**
> **Compaction이 유일한 파일 크기 제어 수단입니다.**

### 3-2. 고카디널리티 (13% 데이터, 카디널리티 11 ~ 277)

버킷 수가 카디널리티보다 작으므로 버킷 수 조정이 직접 효과를 냅니다.

| 버킷 수 | 코드A (35 GB) | 코드B (25 GB) | 코드C (15 GB) | 소규모 (5 GB) |
|---|---|---|---|---|
| **16** | 2,187 MB ❌ | 1,562 MB ❌ | 937 MB ❌ | 312 MB ⚠️ |
| **32** | 1,094 MB ❌ | 781 MB ❌ | 469 MB ⚠️ | 156 MB ✅ |
| **64** | 547 MB ⚠️ | 390 MB ⚠️ | 234 MB ✅ | 78 MB ⚠️ |
| *128 (미테스트)* | 273 MB ✅ | 195 MB ✅ | 117 MB ✅ | 39 MB ❌ |
| *256 (미테스트)* | 137 MB ✅ | 98 MB ⚠️ | 59 MB ❌ | — |

---

## 4. 16 / 32 / 64 버킷 — 256 MB 기준 재판정

| 버킷 수 | 저카디널리티 87% | 고카디널리티 13% | Compaction 필요 범위 | 종합 판정 |
|---|---|---|---|---|
| **16** | 무관 (실효=10) | 코드A~C 모두 1 GB 이상 ❌ | 전체 파티션 | ❌ 배제 |
| **32** | 무관 (실효=10) | 코드A 1 GB ❌, 코드C 469 MB ⚠️ | 대부분 파티션 | ❌ 배제 |
| **64** | 무관 (실효=10) | 코드C 234 MB ✅, 코드A/B 초과 ⚠️ | 코드A/B + 저카디널리티 대용량 | ✅ 조건부 채택 |

### bucket(64) 기준 파티션별 Compaction 필요 여부

| 파티션 | 카디널리티 | Write 후 파일 크기 | 256 MB 달성 방법 |
|---|---|---|---|
| 코드A (35 GB) | 고 | 547 MB ⚠️ | Compaction 필수 (547 → 256 MB, 파일 2개로 분할) |
| 코드B (25 GB) | 고 | 390 MB ⚠️ | Compaction 필수 (390 → 256 MB, 파일 2개로 분할) |
| 코드C (15 GB) | 고 | 234 MB ✅ | 조치 불필요 |
| 저카디널리티 대용량 (≥ 5 GB) | ≤ 10 | 500 MB ~ 3.5 GB ❌ | Compaction 필수 |
| 저카디널리티 소용량 (1~2.5 GB) | ≤ 10 | 200 ~ 250 MB ✅ | 조치 불필요 |
| 저카디널리티 극소 (< 1 GB) | ≤ 10 | < 100 MB ⚠️ | Compaction 권장 (병합) |

---

## 5. 버킷당 파일 수와 조회 성능 (256 MB 기준)

### 5-1. 버킷당 파일 수 권장 기준

| 버킷당 파일 수 | 파일 크기 기준 | 상태 | 조회 성능 영향 |
|---|---|---|---|
| **1개** | 128 ~ 256 MB | ✅✅ 최적 | Bucket Join shuffle 제거, file open 최소 |
| **2 ~ 3개** | 64 ~ 128 MB | ✅ 양호 | Pruning 유효, Join 최적화 일부 유지 |
| **4 ~ 5개** | 32 ~ 64 MB | ⚠️ 주의 | Join 최적화 반감, file open 비용 증가 |
| **6개 이상** | < 32 MB | ❌ 비권장 | 소파일, manifest 증가, planning latency 상승 |

### 5-2. 버킷당 파일이 조회에 미치는 영향

#### Point Lookup (WHERE 버킷컬럼 = 'xxx')

```
hash('xxx') % 64 → 해당 버킷 1개만 스캔

버킷당 파일 1개 (256 MB) → 파일 1개 열면 완료 ✅✅
버킷당 파일 2개 (128 MB) → 파일 2개 오픈, Pruning 유효 ✅
버킷당 파일 10개 (25 MB) → 파일 10개 오픈, 소파일 overhead ❌
```

#### Bucket Join (같은 버킷 수 테이블 간 JOIN)

```
버킷당 파일 1개 → Spark bucket join 인식 → shuffle 생략 ✅✅
버킷당 파일 2개 이상 → 파일 내 정렬 보장 깨짐 → shuffle 재발생 ❌

→ Bucket Join 최적화는 버킷당 파일 1개일 때만 완전히 작동
```

#### Full Scan / 집계

```
버킷 64, 파일 1개/버킷 → 파티션당 64개 파일
버킷 64, 파일 2개/버킷 → 파티션당 128개 파일

파일 수 증가 = file open overhead + manifest scan 증가
→ full scan에서는 버킷당 파일 수를 최소화하는 것이 유리
```

### 5-3. bucket(64) 기준 버킷당 파일 수 목표 (256 MB 기준)

| 파티션 | Write 후 파일 크기 | 버킷당 목표 파일 수 | Compaction 후 파일 수 |
|---|---|---|---|
| 코드A (35 GB, 고카디널리티) | 547 MB | 2개 (256 MB × 2) | 64 버킷 × 2 = 128개 |
| 코드B (25 GB, 고카디널리티) | 390 MB | 2개 (195 MB × 2) | 64 버킷 × 2 = 128개 |
| 코드C (15 GB, 고카디널리티) | 234 MB | **1개** ✅ | 64 버킷 × 1 = 64개 |
| 대용량 저카디널리티 (35 GB) | 3,500 MB | 버킷 무관 → ~137개 목표 | 카디널리티 기준 분포 |
| 소용량 저카디널리티 (1~2.5 GB) | 200~250 MB | **1개** ✅ | 현상 유지 |

---

## 6. 데이터 구간별 통합 전략

```
전체 데이터 (1TB/day, 15 파티션)  ← 목표 파일 크기: 256 MB
│
├── 87% — 카디널리티 ≤ 10 (버킷 수 효과 없음)
│   │
│   ├── 데이터 ≥ 5 GB  (Write 후 파일 500 MB ~ 3.5 GB)
│   │   └── ❌ 256 MB 초과 → Compaction 필수
│   │
│   ├── 데이터 1 ~ 2.5 GB  (Write 후 파일 100 ~ 250 MB)
│   │   └── ✅ 목표 범위 → 조치 불필요
│   │
│   └── 데이터 < 1 GB  (Write 후 파일 < 100 MB)
│       └── ⚠️ 소파일 → Compaction으로 병합 권장
│
└── 13% — 카디널리티 11 ~ 277 (bucket(64) 효과 있음)
    │
    ├── 코드A (35 GB) → Write 후 547 MB → ⚠️ Compaction 필수
    ├── 코드B (25 GB) → Write 후 390 MB → ⚠️ Compaction 필수
    └── 코드C (15 GB) → Write 후 234 MB → ✅ 조치 불필요
```

---

## 7. Compaction 설정 (256 MB 기준)

### 256 MB 초과 파티션 분할 (코드A, 코드B 및 저카디널리티 대용량)

```sql
CALL system.rewrite_data_files(
  table => 'my_catalog.db.my_table',
  strategy => 'binpack',
  options => map(
    'target-file-size-bytes', '268435456',    -- 256 MB
    'min-file-size-bytes',    '134217728',    -- 128 MB (이 이하면 병합 대상)
    'max-file-size-bytes',    '402653184',    -- 384 MB (이 이상이면 분할 대상)
    'max-concurrent-file-group-rewrites', '10'
  ),
  where => "dt = '2026-01-01'"
);
```

### 소파일 병합 (저카디널리티 극소 파티션)

```sql
CALL system.rewrite_data_files(
  table => 'my_catalog.db.my_table',
  strategy => 'binpack',
  options => map(
    'target-file-size-bytes', '268435456',    -- 256 MB
    'min-file-size-bytes',    '67108864',     -- 64 MB (이 이하를 병합 대상으로)
    'max-concurrent-file-group-rewrites', '5'
  ),
  where => "dt = '2026-01-01' AND 특정코드 IN ('소용량코드들')"
);
```

### Write 설정

```sql
ALTER TABLE my_catalog.db.my_table
SET TBLPROPERTIES (
  'write.distribution-mode'            = 'hash',
  'write.target-file-size-bytes'       = '268435456',   -- 256 MB
  'write.parquet.row-group-size-bytes' = '67108864'     -- 64 MB
);
```

---

## 8. 모니터링 쿼리

### 파티션별 파일 크기 및 256 MB 초과 여부 확인

```sql
SELECT
  partition,
  count(*)                                               AS file_count,
  round(avg(file_size_in_bytes) / 1024 / 1024, 1)       AS avg_mb,
  round(max(file_size_in_bytes) / 1024 / 1024, 1)       AS max_mb,
  round(sum(file_size_in_bytes) / 1024 / 1024 / 1024, 2) AS total_gb,
  count(CASE WHEN file_size_in_bytes > 268435456 THEN 1 END) AS over_256mb_count,
  count(CASE WHEN file_size_in_bytes < 67108864  THEN 1 END) AS under_64mb_count
FROM my_catalog.db.my_table.files
WHERE partition.dt = '2026-01-01'
GROUP BY partition
ORDER BY total_gb DESC;
```

### 파티션별 카디널리티 + 실효 버킷 수 확인

```python
from pyspark.sql import functions as F

df = spark.table("my_catalog.db.my_table").filter("dt = '2026-01-01'")

df.groupBy("특정코드") \
  .agg(
      F.countDistinct("버킷컬럼").alias("cardinality"),
      F.count("*").alias("row_count"),
      F.round(F.sum(F.lit(1)) / 1e6, 1).alias("rows_M")
  ) \
  .withColumn("effective_bucket_64", F.least(F.lit(64), F.col("cardinality"))) \
  .withColumn("est_file_mb_64",
      F.round(
          (F.col("rows_M") * F.lit(1700)) /   -- 행당 평균 바이트 추정
          F.col("effective_bucket_64") / 1024 / 1024,
          1
      )
  ) \
  .withColumn("256mb_ok",
      F.when(F.col("est_file_mb_64").between(128, 256), "✅")
       .when(F.col("est_file_mb_64") > 256, "⚠️ Compaction 필요")
       .otherwise("⚠️ 소파일")
  ) \
  .orderBy(F.desc("row_count")) \
  .show(15, truncate=False)
```

### 카디널리티 구간 분포 확인

```python
df.groupBy("특정코드") \
  .agg(F.countDistinct("버킷컬럼").alias("cardinality")) \
  .withColumn(
      "구간",
      F.when(F.col("cardinality") <= 10,  "≤10  (저카디널리티, 버킷 효과 없음)")
       .when(F.col("cardinality") <= 64,  "11~64 (bucket(64) 부분 효과)")
       .otherwise("65~277 (bucket(64) 완전 효과)")
  ) \
  .groupBy("구간").count() \
  .orderBy("구간") \
  .show(truncate=False)
```

---

## 9. 최종 권장 요약

### 버킷 수 결론 (256 MB 기준)

| 버킷 수 | 판정 | 고카디널리티 파티션 파일 크기 | 비고 |
|---|---|---|---|
| **16** | ❌ 배제 | 코드A 2.2 GB, 코드B 1.6 GB | 256 MB 대비 10배 초과 |
| **32** | ❌ 배제 | 코드A 1.1 GB, 코드B 781 MB | 256 MB 대비 4~5배 초과 |
| **64** | ✅ 조건부 채택 | 코드A 547 MB, 코드B 390 MB, 코드C 234 MB | 코드C만 목표 달성, A/B는 Compaction 필요 |
| *128 (미테스트)* | 검토 권장 | 코드A 273 MB, 코드B 195 MB, 코드C 117 MB | 코드A/B 목표 달성 |

### 운영 정책 (bucket(64) 기준)

| 파티션 유형 | 카디널리티 | 데이터량 | Write 후 파일 크기 | 조치 |
|---|---|---|---|---|
| 고카디널리티 대용량 (코드A/B) | 11~277 | ≥ 25 GB | 390~547 MB ⚠️ | **Compaction 필수** (목표 256 MB) |
| 고카디널리티 중용량 (코드C) | 11~277 | 10~25 GB | 156~390 MB ✅/⚠️ | 256 MB 이하 시 불필요 |
| 저카디널리티 대용량 | ≤ 10 | ≥ 5 GB | 500 MB ~ 3.5 GB ❌ | **Compaction 필수** |
| 저카디널리티 적정 | ≤ 10 | 1~2.5 GB | 100~250 MB ✅ | 조치 불필요 |
| 저카디널리티 극소 | ≤ 10 | < 500 MB | < 64 MB ❌ | **Compaction 병합 권장** |

### 핵심 인사이트

```
1. 카디널리티 ≤ 10 (87% 데이터)
   → 버킷 수 조정은 파일 크기에 영향 없음
   → Compaction이 유일한 256 MB 달성 수단

2. 카디널리티 > 64 (13% 데이터)
   → bucket(64)는 256 MB 목표를 코드C(15GB)에서만 충족
   → 코드A(35GB), 코드B(25GB)는 Compaction 병행 필요
   → 256 MB를 Write 단계에서 자연 달성하려면 bucket(128) 검토 필요

3. 버킷당 파일 수 목표: 1개 (256 MB)
   → Bucket Join 최적화 완전 활용
   → full scan file open overhead 최소화
```

---

*작성일: 2026-03-30*
*환경: Apache Spark 3.4.3 / Apache Iceberg / MinIO / Kubernetes (spark-operator)*
*테스트 버킷 수: 16 / 32 / 64 | 목표 파일 크기: 256 MB | 카디널리티: 최대 277, 87% ≤ 10*
