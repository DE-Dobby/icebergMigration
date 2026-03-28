# Iceberg 버킷 설계 가이드

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

버킷수 64  → hash % 64
버킷수 128 → hash % 128
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

## 64 vs 128 버킷 비교

### 파티션별 파일 크기 시뮬레이션

편중 가정 (코드A: 35GB, 코드B: 25GB, 코드C: 15GB, 코드N: 1GB)

| 버킷 수 | 코드A (35GB) | 코드B (25GB) | 코드C (15GB) | 코드N (1GB) |
|---|---|---|---|---|
| **64** | 547MB/파일 ⚠️ | 390MB/파일 ⚠️ | 234MB/파일 ✅ | 데이터 있는 버킷만 생성 ✅ |
| **128** | 273MB/파일 ✅ | 195MB/파일 ✅ | 117MB/파일 ✅ | 데이터 있는 버킷만 생성 ✅ |

### 항목별 비교

| 항목 | 64 버킷 | 128 버킷 |
|---|---|---|
| 파티션당 최대 파일 수 | 64개 | 128개 |
| 일별 최대 총 파일 수 | 15 × 64 = 960개 | 15 × 128 = 1,920개 |
| 대용량 파티션 파일 크기 | 390~547MB ⚠️ 약간 큼 | 195~273MB ✅ 적정 |
| Iceberg manifest 부담 | 낮음 ✅ | 중간 ⚠️ |
| Compaction 비용 | 낮음 ✅ | 중간 ⚠️ |
| 카디널리티 분산 (6,000 기준) | 6,000 / 64 = 93 unique/bucket ✅ | 6,000 / 128 = 46 unique/bucket ✅ |

---

## 조회 패턴별 성능 영향

### 버킷 pruning 작동 시 (WHERE 버킷컬럼 = 'xxx')
```
hash('xxx') % N → 해당 버킷 파일만 읽음

버킷 64  → 전체의 1/64  스캔
버킷 128 → 전체의 1/128 스캔 → 더 빠름 ✅
```

### 버킷 pruning 없을 때 (full scan / 버킷컬럼 조건 없음)
```
버킷 64  → 파티션당 파일 ~64개  열어야 함
버킷 128 → 파티션당 파일 ~128개 열어야 함

→ 파일 수만큼 file open overhead 발생
→ S3/HDFS 환경에서 metadata listing, open 비용 누적
```

### Iceberg Manifest 오버헤드
```
버킷 수 늘어날수록
→ manifest entry 수 증가
→ planning 단계에서 manifest scan 비용 증가
→ 쿼리 시작까지 latency 늘어남

30일 누적 파일 수
버킷 64  → 64 × 15 × 30 = 28,800개
버킷 128 → 128 × 15 × 30 = 57,600개
```

### 조회 패턴별 정리

| 조회 패턴 | 64 버킷 | 128 버킷 |
|---|---|---|
| 버킷컬럼 = 'xxx' (point lookup) | ✅ 빠름 | ✅✅ 더 빠름 |
| 버킷컬럼 IN (...) (multi lookup) | ✅ 빠름 | ✅✅ 더 빠름 |
| DT + 특정코드만 조건 | ✅ 유리 | ⚠️ 파일 수 많음 |
| 전체 집계 / full scan | ✅ 유리 | ⚠️ 파일 수 많음 |

---

## 최종 선택 기준

```
버킷컬럼을 조건절에 자주 쓰는 경우   → 128 권장
풀스캔 / 집계 위주인 경우            → 64 권장
편중이 극심한 경우 (상위 코드 80%+)  → 64 권장
Compaction 리소스 제한적인 경우      → 64 권장
```

### 결론

| 상황 | 추천 버킷 수 |
|---|---|
| 버킷컬럼 point lookup 빈번 | **128** |
| full scan / 집계 위주 | **64** |
| 편중 심함 + 운영 단순화 | **64** |
| 편중 보통 + 쿼리 성능 균형 | **128** |

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
SELECT file_path, file_size_in_bytes / 1024 / 1024 as size_mb
FROM table.files
WHERE partition.dt = 'xxx'
ORDER BY size_mb DESC;
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
