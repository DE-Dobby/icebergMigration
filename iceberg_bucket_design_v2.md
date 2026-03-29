# Iceberg 버킷 설계 가이드 (16 / 32 / 64 / 128 비교)

## 테이블 기본 정보

| 항목 | 값 |
|---|---|
| 일 데이터 | ~6억 건 / 1TB |
| 파티션 구조 | DT × 특정코드 (카디널리티 15) |
| 파티션 수 | 15개 / day |
| 버킷 컬럼 카디널리티 | 5,600 ~ 6,000 |

---

## 버킷 동작 원리

### 버킷 해시 분산
```
hash(버킷컬럼값) % 버킷수 → 버킷번호 결정
```

### 데이터 편중 시 실제 파일 생성 방식
```
코드A (데이터 많음, 35GB)
→ 버킷컬럼 값이 다양하게 분포
→ bucket=0 ~ bucket=N 대부분 채워짐
→ 설정한 버킷 수만큼 파일 생성 ✅

코드N (데이터 적음, 100MB)
→ hash 결과가 몇 개 버킷에만 집중
→ bucket=3, bucket=17, bucket=82 ... 띄엄띄엄
→ 파일 몇 개만 생성, 나머지는 빈 버킷
→ 스몰파일 걱정보다 실제로는 덜 발생 ✅
```

> **핵심**: 버킷 수는 데이터 많은 파티션 기준으로 설계하면,
> 데이터 적은 파티션은 자연스럽게 적은 파일만 생성됨

---

## 버킷당 파일 수와 효율성

### 버킷당 파일 1개가 이상적인 이유
```
버킷의 핵심 가치
→ 같은 버킷번호끼리 이미 hash 정렬되어 있으니
  join/aggregation 시 shuffle 생략

버킷 안에 파일이 여러 개면
→ 버킷 pruning은 되지만 파일 내 정렬 보장 깨짐
→ 버킷 join 최적화 효과 반감
```

### 버킷당 파일 1개로 맞추는 방법
```python
# write 전 repartition을 버킷 수에 맞게
df.repartition(N, F.col("버킷컬럼")) \
  .writeTo("table") \
  .append()

# 또는 distribution-mode 설정
.option("write.distribution-mode", "hash")
```

---

## 버킷 수별 전체 비교 (시뮬레이션)

> 편중 가정: 코드A 35%, 코드B 25%, 코드C 15%, 코드D~N 각 1~3%
> 파티션당 평균 데이터 = 1TB / 15 ≈ 67GB

### 파티션별 파일 크기 시뮬레이션

| 버킷 수 | 코드A (35GB) | 코드B (25GB) | 코드C (15GB) | 코드N (1GB) |
|---|---|---|---|---|
| **16** | 2.2GB/파일 ❌ | 1.6GB/파일 ❌ | 940MB/파일 ⚠️ | 데이터 있는 버킷만 ✅ |
| **32** | 1.1GB/파일 ❌ | 781MB/파일 ⚠️ | 469MB/파일 ⚠️ | 데이터 있는 버킷만 ✅ |
| **64** | 547MB/파일 ⚠️ | 390MB/파일 ⚠️ | 234MB/파일 ✅ | 데이터 있는 버킷만 ✅ |
| **128** | 273MB/파일 ✅ | 195MB/파일 ✅ | 117MB/파일 ✅ | 데이터 있는 버킷만 ✅ |

### 항목별 종합 비교

| 항목 | 16 버킷 | 32 버킷 | 64 버킷 | 128 버킷 |
|---|---|---|---|---|
| 파티션당 최대 파일 수 | 16개 | 32개 | 64개 | 128개 |
| 일별 최대 총 파일 수 | 240개 | 480개 | 960개 | 1,920개 |
| 30일 누적 파일 수 | 7,200개 | 14,400개 | 28,800개 | 57,600개 |
| 대용량 파티션 파일 크기 | 1.6~2.2GB ❌ 너무 큼 | 780MB~1.1GB ❌ 큼 | 390~547MB ⚠️ 약간 큼 | 195~273MB ✅ 적정 |
| 스몰파일 위험 | 없음 ✅ | 없음 ✅ | 낮음 ✅ | 낮음 ✅ |
| Iceberg manifest 부담 | 매우 낮음 ✅ | 낮음 ✅ | 낮음 ✅ | 중간 ⚠️ |
| Compaction 비용 | 매우 낮음 ✅ | 낮음 ✅ | 낮음 ✅ | 중간 ⚠️ |
| 버킷 pruning 효율 | 매우 낮음 ❌ | 낮음 ❌ | 중간 ⚠️ | 높음 ✅ |
| Spark OOM 위험 | 높음 ❌ | 중간 ⚠️ | 낮음 ✅ | 낮음 ✅ |
| 카디널리티 분산 (6,000 기준) | 375 unique/bucket ✅ | 188 unique/bucket ✅ | 93 unique/bucket ✅ | 46 unique/bucket ✅ |

---

## 16, 32 버킷을 배제하는 이유

### 16 버킷
```
❌ 대용량 파티션 파일이 1.6~2.2GB
   → Parquet row group 메모리 압박
   → Spark task당 처리량 과다, OOM 위험
   → 파일 open 시 전체 로딩 부담 증가
   → 버킷 pruning 효과 1/16 수준으로 미미
   → 현재 데이터 규모(1TB/day)에서 사실상 부적합
```

### 32 버킷
```
⚠️ 대용량 파티션 파일이 780MB~1.1GB
   → 허용 범위를 벗어남 (Iceberg 권장 128~512MB)
   → 코드B 이상 파티션에서 파일 크기 문제 발생
   → 버킷 pruning 효과 1/32로 여전히 낮음
   → 16보다는 낫지만 64 대비 이점 없음
```

---

## 조회 패턴별 성능 영향

### 버킷 pruning 작동 시 (WHERE 버킷컬럼 = 'xxx')
```
hash('xxx') % N → 해당 버킷 파일만 읽음

버킷 16  → 전체의 1/16  스캔 ❌
버킷 32  → 전체의 1/32  스캔 ⚠️
버킷 64  → 전체의 1/64  스캔 ✅
버킷 128 → 전체의 1/128 스캔 ✅✅ 가장 빠름
```

### 버킷 pruning 없을 때 (full scan / 버킷컬럼 조건 없음)
```
버킷 수가 적을수록 파일 수 적어 file open overhead 감소
버킷 수가 많을수록 manifest entry 증가 → planning latency 증가

버킷 16  → 240개/day   manifest 부담 최소 ✅
버킷 32  → 480개/day   manifest 부담 낮음 ✅
버킷 64  → 960개/day   manifest 부담 낮음 ✅
버킷 128 → 1,920개/day manifest 부담 중간 ⚠️
```

### 조회 패턴별 정리

| 조회 패턴 | 16 | 32 | 64 | 128 |
|---|---|---|---|---|
| 버킷컬럼 = 'xxx' (point lookup) | ❌ | ⚠️ | ✅ | ✅✅ |
| 버킷컬럼 IN (...) (multi lookup) | ❌ | ⚠️ | ✅ | ✅✅ |
| DT + 특정코드만 조건 | ✅✅ | ✅✅ | ✅ | ⚠️ |
| 전체 집계 / full scan | ✅✅ | ✅✅ | ✅ | ⚠️ |

---

## 최종 선택 기준

```
16, 32  →  현재 데이터 규모에서 파일 크기 문제로 배제
64      →  full scan / 집계 위주, 편중 심함, compaction 리소스 제한
128     →  버킷컬럼 point lookup 빈번, 쿼리 성능 최우선
```

### 결론 요약

| 상황 | 추천 버킷 수 |
|---|---|
| 버킷컬럼 point lookup 빈번 | **128** |
| full scan / 집계 위주 | **64** |
| 편중 심함 + 운영 단순화 | **64** |
| 편중 보통 + 쿼리 성능 균형 | **128** |
| 현재 규모에서 배제 대상 | ~~16~~, ~~32~~ |

---

## 운영 관리

### 버킷 편중 모니터링
```python
df.groupBy("특정코드") \
  .count() \
  .withColumn("비율", F.col("count") / F.sum("count").over(Window.partitionBy())) \
  .orderBy(F.desc("count")) \
  .show(15)
```

### 파일 크기 분포 확인
```sql
SELECT
  partition,
  count(*) as file_count,
  round(avg(file_size_in_bytes) / 1024 / 1024, 1) as avg_size_mb,
  round(max(file_size_in_bytes) / 1024 / 1024, 1) as max_size_mb,
  round(min(file_size_in_bytes) / 1024 / 1024, 1) as min_size_mb
FROM table.files
WHERE partition.dt = 'xxx'
GROUP BY partition
ORDER BY avg_size_mb DESC;
```

### Manifest 정리 (128 버킷 사용 시 특히 중요)
```sql
-- manifest 정리
CALL system.rewrite_manifests('db.table');

-- 오래된 snapshot 정리
CALL system.expire_snapshots(
  table => 'db.table',
  older_than => TIMESTAMP '2025-01-01 00:00:00'
);
```

### Compaction (소규모 파티션 대상)
```sql
CALL system.rewrite_data_files(
  table => 'db.table',
  strategy => 'binpack',
  options => map(
    'target-file-size-bytes', '268435456',
    'min-file-size-bytes',    '67108864',
    'max-concurrent-file-group-rewrites', '10'
  ),
  where => "dt = '2025-01-01' AND 특정코드 IN ('코드C','코드D')"
);
```

---

> **주의**: 버킷 수는 나중에 변경 시 전체 rewrite 필요.
> 처음 설계를 신중하게 결정할 것.
