# Iceberg 버킷 사이즈 테스트 가이드 (16 / 32 / 64)

> 실제 테스트 환경 기준으로 16, 32, 64 버킷 수를 비교하고,
> 조회 성능을 위한 버킷당 파일 수 권장 기준을 정리한 문서입니다.

---

## 1. 테스트 환경 및 테이블 기본 정보

| 항목 | 값 |
|---|---|
| 일 데이터량 | ~6억 건 / 1 TB |
| 파티션 구조 | `DT × 특정코드` (카디널리티 15) |
| 파티션 수 | 15개 / day |
| 파티션당 평균 데이터 | 1 TB ÷ 15 ≈ **67 GB** |
| 버킷 컬럼 카디널리티 | 5,600 ~ 6,000 |
| 파일 크기 권장 범위 | 256 MB ~ 512 MB |
| 테스트 버킷 수 | **16 / 32 / 64** |
| 실행 환경 | Apache Spark 3.4.3 / Apache Iceberg / MinIO / Kubernetes (spark-operator) |

---

## 2. 버킷 수별 파일 크기 시뮬레이션

> 데이터 편중 가정: 코드A 35%, 코드B 25%, 코드C 15%, 코드D~N 각 1~3%

### 파티션별 파일 크기

| 버킷 수 | 코드A (35GB) | 코드B (25GB) | 코드C (15GB) | 소규모 코드 (1GB) |
|---|---|---|---|---|
| **16** | 2,187 MB ❌ | 1,562 MB ❌ | 937 MB ❌ | 데이터 있는 버킷만 생성 ✅ |
| **32** | 1,094 MB ❌ | 781 MB ⚠️ | 469 MB ⚠️ | 데이터 있는 버킷만 생성 ✅ |
| **64** | 547 MB ⚠️ | 390 MB ✅ | 234 MB ✅ | 데이터 있는 버킷만 생성 ✅ |

### 판정 기준

| 파일 크기 | 상태 | 이유 |
|---|---|---|
| < 128 MB | ❌ 소파일 | 메타데이터 오버헤드, planning latency 증가 |
| 128 MB ~ 256 MB | ⚠️ 허용 미만 | 기능은 하지만 최적 미달 |
| **256 MB ~ 512 MB** | ✅ 권장 | Parquet 효율 + 쿼리 성능 균형 |
| > 1 GB | ❌ 과대 파일 | row group 메모리 압박, Spark OOM, pruning 효율 저하 |

---

## 3. 버킷 수별 종합 비교

| 항목 | 16 버킷 | 32 버킷 | 64 버킷 |
|---|---|---|---|
| 파티션당 최대 파일 수 | 16개 | 32개 | 64개 |
| 일별 최대 총 파일 수 | 240개 | 480개 | 960개 |
| 30일 누적 파일 수 | 7,200개 | 14,400개 | 28,800개 |
| 대용량 파티션 파일 크기 | 1.6 ~ 2.2 GB ❌ | 780 MB ~ 1.1 GB ❌ | 390 ~ 547 MB ⚠️ |
| 스몰파일 위험 | 없음 ✅ | 없음 ✅ | 낮음 ✅ |
| Iceberg manifest 부담 | 매우 낮음 ✅ | 낮음 ✅ | 낮음 ✅ |
| Compaction 비용 | 매우 낮음 ✅ | 낮음 ✅ | 낮음 ✅ |
| 버킷 pruning 효율 (1/N 스캔) | 1/16 ❌ | 1/32 ⚠️ | 1/64 ✅ |
| Spark OOM 위험 | 높음 ❌ | 중간 ⚠️ | 낮음 ✅ |
| 카디널리티 분산 (6,000 기준) | 375 unique/bucket ✅ | 188 unique/bucket ✅ | 93 unique/bucket ✅ |

---

## 4. 16, 32 버킷을 배제하는 이유

### 16 버킷 — ❌ 배제

```
대용량 파티션(코드A, B) 파일이 1.6 ~ 2.2 GB
→ Parquet row group 메모리 압박
→ Spark task당 처리량 과다, OOM 발생 가능
→ 버킷 pruning 효과 1/16 수준 (64의 1/4)
→ 현재 데이터 규모(1 TB/day)에서 사실상 부적합
```

### 32 버킷 — ⚠️ 배제

```
대용량 파티션(코드A) 파일이 780 MB ~ 1.1 GB
→ 권장 범위(256 ~ 512 MB)를 2배 초과
→ 코드B 파티션도 781 MB로 경계 초과
→ 버킷 pruning 효과 1/32 (64의 절반)
→ 16보다 개선됐지만 64 대비 명확한 이점 없음
```

### 64 버킷 — ✅ 채택

```
코드B, C 파티션 파일이 권장 범위(256 ~ 512 MB) 내
코드A 파티션은 547 MB로 약간 초과하지만 허용 수준
버킷 pruning 효율 1/64
manifest 부담 낮음, compaction 비용 낮음
스몰파일 위험 낮음
```

---

## 5. 버킷당 파일 수와 조회 성능

### 5-1. 버킷당 파일 수 권장 기준

버킷당 파일 수는 **쓰기 후 생성된 파일 수 기준**으로 판단합니다.

| 버킷당 파일 수 | 상태 | 이유 |
|---|---|---|
| **1개** | ✅✅ 최적 | shuffle 제거, bucket join 완전 최적화, 최소 file open |
| **2 ~ 3개** | ✅ 양호 | bucket pruning 작동, join 최적화 일부 유지, 파일 크기 128 MB 이상이면 허용 |
| **4 ~ 5개** | ⚠️ 주의 | pruning은 되지만 join 최적화 반감, 파일 오픈 비용 증가 |
| **6개 이상** | ❌ 비권장 | 스몰파일 가능성, manifest 엔트리 증가, planning latency 상승 |

### 5-2. 버킷당 파일이 조회에 미치는 영향

#### Point Lookup (WHERE 버킷컬럼 = 'xxx')

```
hash('xxx') % 64 → 해당 버킷 1개만 스캔

버킷당 파일 1개 → 파일 1개만 열면 됨 ✅✅
버킷당 파일 3개 → 파일 3개 오픈 (동일 버킷이므로 pruning은 유효) ✅
버킷당 파일 10개 → 파일 10개 오픈, 메타데이터 오버헤드 증가 ⚠️
```

#### Bucket Join (같은 버킷 수끼리 JOIN)

```
버킷당 파일 1개 → Spark가 bucket join 인식 → shuffle 0 ✅✅
버킷당 파일 2개 이상 → 같은 버킷 내 정렬 보장 안됨
                     → shuffle 발생 또는 sort-merge fallback ❌
```

> **Bucket Join 최적화는 버킷당 파일 1개일 때만 완전히 작동합니다.**

#### Full Scan / 집계 (버킷 컬럼 조건 없음)

```
64 버킷, 파일 1개/버킷 → 파티션당 파일 64개
64 버킷, 파일 2개/버킷 → 파티션당 파일 128개

파일 수 증가 = file open overhead 증가 = planning latency 증가
→ full scan에서는 버킷당 파일 수를 최소화하는 것이 유리
```

### 5-3. 64 버킷 기준 버킷당 파일 수 목표

| 파티션 코드 | 데이터량 | 버킷당 파일 1개 시 파일 크기 | 권장 여부 |
|---|---|---|---|
| 코드A | 35 GB | 547 MB (약간 초과) | ⚠️ 1~2개 혼용 허용 |
| 코드B | 25 GB | 390 MB | ✅ 1개 목표 |
| 코드C | 15 GB | 234 MB | ✅ 1개 목표 |
| 소규모 코드 | 1 GB | 버킷 몇 개에만 생성 | ✅ 자연 최소화 |

> 코드A 파티션에서 파일이 500 MB 초과될 경우, Compaction을 통해
> `target-file-size = 256 MB`로 2개로 분할하는 것이 허용됩니다.

---

## 6. 조회 패턴별 성능 비교

| 조회 패턴 | 16 버킷 | 32 버킷 | 64 버킷 |
|---|---|---|---|
| 버킷컬럼 = 'xxx' (point lookup) | ❌ 1/16 스캔 | ⚠️ 1/32 스캔 | ✅ 1/64 스캔 |
| 버킷컬럼 IN (...) (multi lookup) | ❌ | ⚠️ | ✅ |
| Bucket Join (shuffle 제거) | ❌ 효과 낮음 | ⚠️ 효과 낮음 | ✅ 효과 높음 |
| DT + 특정코드만 조건 | ✅✅ 파일 수 적음 | ✅✅ 파일 수 적음 | ✅ |
| 전체 집계 / full scan | ✅✅ | ✅✅ | ✅ |
| Spark write OOM 위험 | ❌ 높음 | ⚠️ 중간 | ✅ 낮음 |

---

## 7. 버킷당 파일 1개로 맞추는 방법

### Write 시 repartition 적용

```python
# 버킷 수와 동일하게 repartition 후 write
df.repartition(64, F.col("버킷컬럼")) \
  .writeTo("my_catalog.db.my_table") \
  .append()
```

### distribution-mode 설정

```python
spark.sql("""
  ALTER TABLE my_catalog.db.my_table
  SET TBLPROPERTIES ('write.distribution-mode' = 'hash')
""")
```

### Compaction으로 파일 수 줄이기 (운영 중 정리)

```sql
-- 버킷당 파일이 여러 개인 파티션 정리
CALL system.rewrite_data_files(
  table => 'my_catalog.db.my_table',
  strategy => 'binpack',
  options => map(
    'target-file-size-bytes', '268435456',   -- 256 MB
    'min-file-size-bytes',    '134217728',   -- 128 MB
    'max-concurrent-file-group-rewrites', '10'
  ),
  where => "dt = '2026-01-01'"
);
```

---

## 8. 파일 수 및 크기 모니터링 쿼리

### 버킷당 파일 수 확인

```sql
SELECT
  partition,
  count(*)                                          AS file_count,
  round(avg(file_size_in_bytes) / 1024 / 1024, 1)  AS avg_size_mb,
  round(max(file_size_in_bytes) / 1024 / 1024, 1)  AS max_size_mb,
  round(min(file_size_in_bytes) / 1024 / 1024, 1)  AS min_size_mb
FROM my_catalog.db.my_table.files
WHERE partition.dt = '2026-01-01'
GROUP BY partition
ORDER BY file_count DESC;
```

### 스몰파일 존재 여부 확인

```sql
SELECT
  partition,
  count(*) AS small_file_count
FROM my_catalog.db.my_table.files
WHERE partition.dt = '2026-01-01'
  AND file_size_in_bytes < 134217728   -- 128 MB 미만
GROUP BY partition
HAVING count(*) > 0;
```

### 데이터 편중 확인

```python
from pyspark.sql import functions as F, Window

df = spark.table("my_catalog.db.my_table") \
          .filter("dt = '2026-01-01'")

df.groupBy("특정코드") \
  .count() \
  .withColumn(
      "비율",
      F.round(F.col("count") / F.sum("count").over(Window.partitionBy(F.lit(1))), 4)
  ) \
  .orderBy(F.desc("count")) \
  .show(15)
```

---

## 9. 테스트 결론 요약

### 버킷 수 선택

| 테스트 결과 | 판정 | 이유 |
|---|---|---|
| **16 버킷** | ❌ 배제 | 파일 1.6~2.2 GB → OOM 위험, pruning 효과 미미 |
| **32 버킷** | ❌ 배제 | 파일 780 MB~1.1 GB → 권장 범위 2배 초과 |
| **64 버킷** | ✅ 채택 | 파일 390~547 MB → 허용 수준, pruning 1/64 |

### 버킷당 파일 수 목표

| 기준 | 목표 |
|---|---|
| 최적 (Bucket Join + 최소 overhead) | **1개/버킷** |
| 허용 범위 (pruning 유효, 파일 크기 ≥ 128 MB) | **2 ~ 3개/버킷** |
| 코드A처럼 파일이 500 MB 초과 시 | **2개/버킷으로 Compaction** |
| 6개 이상이면 Compaction 실행 | **필수** |

### 운영 권장 설정 (64 버킷 기준)

```python
# Spark write 설정
spark.conf.set("spark.sql.shuffle.partitions", "64")

# 테이블 write distribution
ALTER TABLE my_catalog.db.my_table
SET TBLPROPERTIES (
  'write.distribution-mode'        = 'hash',
  'write.target-file-size-bytes'   = '536870912',   -- 512 MB
  'write.parquet.row-group-size-bytes' = '134217728' -- 128 MB
);
```

---

*작성일: 2026-03-30*
*환경: Apache Spark 3.4.3 / Apache Iceberg / MinIO / Kubernetes (spark-operator)*
*테스트 버킷 수: 16 / 32 / 64*
