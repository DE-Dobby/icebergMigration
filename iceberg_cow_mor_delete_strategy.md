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

## 현재 구조 기준 선택

```
10분 텀 빈번한 삭제
→ COW : 데이터 파일 재작성 비용 폭증 ❌
→ MOR : Delete 파일 생성, Compaction으로 관리 ✅

결론: MOR 선택
단, Compaction 자동화 필수
```

---

## 10분 텀 DELETE 기준 파일 생성량 (MOR 기준)

> **현실적 추정 기준**: 카디널리티 277 / 버킷 64 → 버킷당 평균 ~4개 고유값 → 1회 DELETE 시 영향 버킷 ~10개

### 데이터 파일 생성량

| 기준 | 낙관적 (1개/회) | 현실적 (10개/회) | 비관적 (64개/회) |
|---|---|---|---|
| 1회 | 1개 | 10개 | 64개 |
| 1일 | 144개 | 1,440개 | 9,216개 |
| 30일 | 4,320개 | 43,200개 | 276,480개 |

### 메타데이터 파일 생성량

DELETE 1회 = 1 commit = 아래 메타데이터가 **모두** 생성됩니다.

| 메타데이터 종류 | 역할 | 1회 생성 수 | 1일 (144회) | 30일 |
|---|---|---|---|---|
| **Snapshot** | 테이블 상태 스냅샷 (manifest list) | 1개 | 144개 | 4,320개 |
| **Manifest 파일** | Delete 파일 목록 기록 | 1 ~ 2개 | ~216개 | ~6,480개 |
| **Delete 파일** | 실제 삭제 레코드 | 10개 (현실적) | 1,440개 | 43,200개 |

#### 메타데이터가 쿼리에 미치는 영향

```
Iceberg 쿼리 실행 흐름:
  1. 최신 Snapshot 로드
  2. Snapshot → Manifest List 읽기
  3. Manifest List → 모든 Manifest 파일 스캔
  4. Manifest → 데이터 파일 + Delete 파일 목록 확보
  5. 데이터 파일 읽기 + Delete 파일 적용

Manifest 파일이 누적될수록 step 3 비용 증가
→ Delete 파일 43,200개 × 매니페스트 항목 = planning latency 증가 ❌

Compaction 미실행 시
→ 쿼리마다 수만 개의 Delete 파일 존재 여부를 확인해야 함
→ 응답 속도 저하
```

#### 30일 누적 메타데이터 규모 (Compaction 없을 때)

| 파일 종류 | 30일 누적 수 | 개당 크기 | 예상 총 크기 |
|---|---|---|---|
| Snapshot | 4,320개 | ~2 KB | ~9 MB |
| Manifest | ~6,480개 | ~50 KB | ~324 MB |
| Delete 파일 | ~43,200개 | ~5 KB | ~216 MB |
| **합계** | **~54,000개** | — | **~549 MB** |

> 스토리지 크기보다 **파일 수와 Manifest 스캔 비용**이 핵심 문제

---

## Delete 파일 생성 위치

```
DELETE FROM table
WHERE dt = '2026-03-30' AND code = 'A' AND id = 'ABC'

→ 영향받는 파티션 내 데이터 파일 위치에 생성

data/dt=2026-03-30/code=A/bucket_0/
    data-001.parquet         ← 데이터 파일
    eq-delete-001.parquet    ← Equality Delete 파일 (신규 생성)
```

### 파티션 조건 없을 때 주의

```
DELETE FROM table WHERE id = 'ABC'  -- 파티션 조건 없음
→ 모든 파티션 스캔 + 모든 파티션에 Delete 파일 생성 가능 ❌

DELETE FROM table
WHERE dt = '2026-03-30' AND code = 'A' AND id = 'ABC'
→ 특정 파티션만 영향 ✅
```

> DELETE 쿼리에 반드시 파티션 조건(dt, code) 포함 필요

---

## Compaction 전략

### 주기 설계 (단일 전략)

| 항목 | 값 |
|---|---|
| 대상 | 전체 파티션 (구분 없음) |
| 주기 | **4시간** |
| delete-file-threshold | **5** (버킷당 Delete 파일 5개 초과 시 해당 버킷 재작성) |
| 목표 파일 크기 | 256 MB |

> 10분 × 144회/일 → 파티션당 평균 10회/일 삭제 도달 → 4시간 주기로 threshold 5 소화 가능

### rewrite_data_files (Delete 파일 흡수 + 파일 크기 정리)

```sql
CALL catalog.system.rewrite_data_files(
  table => 'db.table',
  strategy => 'binpack',
  options => map(
    'delete-file-threshold',              '5',
    'target-file-size-bytes',             '268435456',   -- 256 MB
    'min-file-size-bytes',                '134217728',   -- 128 MB
    'max-file-size-bytes',                '402653184',   -- 384 MB
    'max-concurrent-file-group-rewrites', '10'
  ),
  where => 'dt = current_date'
);
```

### rewrite_manifests (Manifest 파일 정리)

rewrite_data_files 이후 Manifest 파일도 별도로 정리합니다.
Delete 파일이 많을수록 Manifest 조각화가 심해져 **쿼리 planning latency**가 증가합니다.

```sql
CALL catalog.system.rewrite_manifests('db.table');
```

### expire_snapshots (스냅샷 + 오래된 Manifest 정리)

```sql
CALL catalog.system.expire_snapshots(
  table       => 'db.table',
  older_than  => now() - INTERVAL 3 DAYS,
  retain_last => 3
);
```

> expire_snapshots 실행 후 더 이상 참조되지 않는 Manifest와 Delete 파일이
> 파일 시스템에 남을 수 있으므로 주기적으로 orphan 파일도 정리

```sql
CALL catalog.system.remove_orphan_files(
  table       => 'db.table',
  older_than  => now() - INTERVAL 5 DAYS
);
```

### 전체 실행 순서

```
1. rewrite_data_files  → Delete 파일 흡수, 데이터 파일 크기 정리
2. rewrite_manifests   → Manifest 조각 정리, planning latency 감소
3. expire_snapshots    → 오래된 Snapshot + Manifest 제거
4. remove_orphan_files → 참조 끊긴 파일 정리 (주 1회)
```

---

## 모니터링 쿼리

### Delete 파일 현황

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

### Snapshot / Manifest 누적 현황

```sql
-- 스냅샷 수 확인
SELECT COUNT(*) AS snapshot_count
FROM catalog.db.table.snapshots;

-- Manifest 파일 수 확인
SELECT COUNT(*) AS manifest_count
FROM catalog.db.table.manifests;
```

### 데이터 파일 vs Delete 파일 비율

```sql
SELECT
  content,
  COUNT(*)                                               AS file_count,
  round(sum(file_size_in_bytes) / 1024 / 1024 / 1024, 2) AS total_gb
FROM catalog.db.table.files
GROUP BY content;
-- content: 0 = data file, 1 = equality delete, 2 = position delete
```

---

## 운영 요약

```
MOR 선택 — Compaction 자동화 필수

[파일 생성 (1일 기준, 현실적 추정)]
  데이터 파일 (Delete)  : ~1,440개
  Snapshot             : 144개
  Manifest             : ~216개
  합계                  : ~1,800개/일

[Compaction 스케줄 — 단일 전략]
  주기             : 4시간
  대상             : 전체 파티션 (dt = current_date)
  threshold        : delete-file-threshold = 5
  target-file-size : 256 MB

[실행 순서]
  1. rewrite_data_files  (4시간 주기)
  2. rewrite_manifests   (4시간 주기, rewrite_data_files 직후)
  3. expire_snapshots    (일 1회 새벽, retain_last = 3)
  4. remove_orphan_files (주 1회)
```

---

*작성일: 2026-03-31*
*환경: Apache Spark 3.4.3 / Apache Iceberg / MinIO / Kubernetes (spark-operator)*
