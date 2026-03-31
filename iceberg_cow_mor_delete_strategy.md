# Iceberg COW vs MOR Delete 전략

## 전제 조건

| 항목 | 값 |
|---|---|
| 버킷 수 | 64 |
| 삭제 주기 | 10분 (하루 144회) |
| 파티션 구조 | DT × code (15개, 상위 3개가 80%) |
| 버킷 컬럼 카디널리티 | 277 (불균등 분포) |

---

## COW vs MOR 기본 비교

| 항목 | COW | MOR |
|---|---|---|
| DELETE 동작 | 데이터 파일 재작성 | Delete 파일 생성 |
| 쓰기 속도 | 느림 ❌ | 빠름 ✅ |
| 읽기 속도 | 빠름 ✅ | Delete 파일 누적 시 느려짐 ❌ |
| Delete 파일 | 없음 | 있음 |
| Compaction 필요성 | 낮음 | 높음 ❌ |
| 10분 텀 삭제 적합성 | ❌ | ✅ |

---

## 10분 텀 DELETE 기준 파일 생성량

### COW

```
버킷 64 기준
1회 DELETE → 영향받은 데이터 파일 전체 재작성
현실적 추정 10개 버킷 영향 → 데이터 파일 10개 재작성/회

1일 = 144회 × 10개 = 1,440개 재작성
파일 크기 = 원본과 동일 (수백 MB)
→ 스토리지/쓰기 비용 폭증 ❌
→ 현실적으로 불가 수준
```

### MOR

| 기준 | 낙관적 (1개/회) | 현실적 (10개/회) | 비관적 (64개/회) |
|---|---|---|---|
| 1회 | 1개 | 10개 | 64개 |
| 1일 | 144개 | 1,440개 | 9,216개 |
| 30일 | 4,320개 | 43,200개 | 276,480개 |

> **현실적 추정 기준**: 카디널리티 277 / 버킷 64 → 버킷당 평균 ~4개 고유값 → 1회 DELETE 시 영향 버킷 ~10개

---

## 현재 구조 기준 선택

```
10분 텀 빈번한 삭제
→ COW : 데이터 파일 재작성 비용 폭증 ❌
→ MOR : Delete 파일 생성, Compaction으로 관리 ✅

결론: MOR 선택이 맞음
단, Compaction 자동화 필수
```

---

## Delete 파일 생성 위치

```
DELETE FROM table
WHERE dt = '2026-03-30' AND code = 'A' AND id = 'ABC'

→ 영향받는 파티션 내 데이터 파일 위치에 생성

data/dt=2026-03-30/code=A/bucket_0/
    data-001.parquet         ← 데이터 파일
    eq-delete-001.parquet    ← Equality Delete 파일
```

### 파티션 조건 없을 때 주의

```
DELETE FROM table WHERE id = 'ABC'  -- 파티션 조건 없음
→ 모든 파티션에 Delete 파일 생성 가능 ❌

DELETE FROM table
WHERE dt = '2026-03-30' AND code = 'A' AND id = 'ABC'
→ 특정 파티션만 영향 ✅
```

> DELETE 쿼리에 반드시 파티션 조건(dt, code) 포함 필요

---

## Compaction 전략

### 주기 설계

| 대상 | 주기 | 임계값 |
|---|---|---|
| 핫 파티션 (code A, B, C) | 4시간 | Delete 파일 5개 초과 |
| 콜드 파티션 (나머지) | 일 1회 새벽 | Delete 파일 5개 초과 |

### 핫 파티션 Compaction

```sql
CALL catalog.system.rewrite_data_files(
  table => 'db.table',
  strategy => 'binpack',
  options => map(
    'delete-file-threshold', '5',
    'target-file-size-bytes', '268435456'   -- 256 MB
  ),
  where => 'dt = current_date
            AND code IN (''A'',''B'',''C'')'
);
```

### 콜드 파티션 Compaction

```sql
CALL catalog.system.rewrite_data_files(
  table => 'db.table',
  strategy => 'binpack',
  options => map(
    'delete-file-threshold', '5',
    'target-file-size-bytes', '268435456'   -- 256 MB
  ),
  where => 'dt = current_date
            AND code NOT IN (''A'',''B'',''C'')'
);
```

### 스냅샷 만료

```sql
CALL catalog.system.expire_snapshots(
  table => 'db.table',
  older_than => TIMESTAMP '2026-03-28 00:00:00',
  retain_last => 3
);
```

---

## Delete 파일 현황 모니터링

```sql
SELECT
  partition,
  COUNT(*)          AS delete_file_count,
  SUM(record_count) AS delete_records
FROM catalog.db.table.files
WHERE content = 1   -- 1 = delete file
GROUP BY partition
ORDER BY delete_file_count DESC
LIMIT 20;
```

---

## 운영 요약

```
COW  → 10분 텀 삭제에 부적합 (파일 재작성 비용 폭증)
MOR  → 적합, 단 Compaction 자동화 필수

Compaction
→ 핫 파티션 (code A, B, C) : 4시간 주기
→ 콜드 파티션 (나머지)      : 일 1회 새벽
→ delete-file-threshold    : 5

스냅샷 만료 : 3일 보관, 일 1회
```

---

*작성일: 2026-03-30*
*환경: Apache Spark 3.4.3 / Apache Iceberg / MinIO / Kubernetes (spark-operator)*
