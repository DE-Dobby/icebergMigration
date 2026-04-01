# Iceberg COW vs MOR Delete 전략

## 전제 조건

| 항목 | 값 |
|---|---|
| 버킷 수 | 64 |
| **버킷당 파일 수** | **1 ~ 80개 (데이터 불균형)** |
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

## 버킷당 파일 1~80개가 만드는 문제

### 총 데이터 파일 수 규모

```
파티션 1개 (코드 1개, 1일) 기준:
  최솟값: 64 버킷 × 1개  =     64개 파일
  평균값: 64 버킷 × 40개 =  2,560개 파일
  최댓값: 64 버킷 × 80개 =  5,120개 파일

전체 15 파티션 × 평균 2,560개 = 38,400개 파일/일
```

### MOR Delete 적용 비용 (읽기 시점)

MOR은 쿼리 실행 시 데이터 파일 + Delete 파일을 **매번** 합산합니다.

```
버킷당 파일 1개 + Delete 파일 5개 →  1 × 5 =   5번 merge 연산
버킷당 파일 40개 + Delete 파일 5개 → 40 × 5 = 200번 merge 연산
버킷당 파일 80개 + Delete 파일 5개 → 80 × 5 = 400번 merge 연산
```

> **버킷당 파일이 80개이면 Delete 파일 1개만 있어도 80번의 파일 open + 적용 발생**
> Compaction으로 버킷당 파일 수를 줄이는 것이 DELETE 이후 조회 성능의 핵심

---

## 10분 텀 DELETE 기준 파일 생성량 (MOR 기준)

> 현실적 추정: 카디널리티 277 / 버킷 64 → 버킷당 평균 ~4개 고유값 → 1회 DELETE 시 영향 버킷 ~10개

### 데이터 파일 (Delete 파일)

| 기준 | 낙관 (1개/회) | 현실 (10개/회) | 비관 (64개/회) |
|---|---|---|---|
| 1회 | 1개 | 10개 | 64개 |
| 1일 | 144개 | 1,440개 | 9,216개 |
| 30일 | 4,320개 | 43,200개 | 276,480개 |

### 메타데이터 파일 생성량

DELETE 1회 = 1 commit = 아래 메타데이터가 **모두** 생성됩니다.

| 메타데이터 종류 | 역할 | 1회 | 1일 (144회) | 30일 |
|---|---|---|---|---|
| **Snapshot** | 테이블 상태 전체 | 1개 | 144개 | 4,320개 |
| **Manifest 파일** | 파일 목록 기록 | 1 ~ 2개 | ~216개 | ~6,480개 |
| **Delete 파일** | 삭제 레코드 | 10개 | 1,440개 | 43,200개 |

### 누적 데이터 파일(기존 데이터 포함) 기준 Manifest 부담

```
파티션당 평균 데이터 파일: 64 버킷 × 40개 = 2,560개
15 파티션: 2,560 × 15 = 38,400개

Manifest 1개당 ~1,000 항목 수용
→ 데이터 파일만으로도 Manifest ~38개 / 일 필요

여기에 DELETE 커밋 144회 × 1~2 Manifest = +216개/일
→ 하루 총 Manifest 생성: ~254개
→ 30일 누적: ~7,620개
```

### 30일 누적 전체 메타데이터 규모 (Compaction 없을 때)

| 파일 종류 | 30일 누적 수 | 개당 크기 | 예상 총 크기 |
|---|---|---|---|
| Snapshot | 4,320개 | ~2 KB | ~9 MB |
| Manifest (데이터용) | ~1,140개 | ~50 KB | ~57 MB |
| Manifest (Delete용) | ~6,480개 | ~50 KB | ~324 MB |
| 데이터 파일 | ~1,152,000개 | 수 MB ~ 수백 MB | TB 단위 |
| Delete 파일 | ~43,200개 | ~5 KB | ~216 MB |

> 스토리지보다 **파일 수와 Manifest 스캔 비용**이 핵심 문제
> 쿼리마다 수천 개의 Manifest를 열고 수십만 개 항목을 스캔해야 함

---

## Delete 파일 생성 위치

```
DELETE FROM table
WHERE dt = '2026-03-30' AND code = 'A' AND id = 'ABC'

→ 영향받는 파티션 내 데이터 파일 위치에 생성

data/dt=2026-03-30/code=A/bucket_0/
    data-001.parquet         ← 데이터 파일 (80개 있을 수도 있음)
    data-002.parquet
    ...
    data-080.parquet
    eq-delete-001.parquet    ← Equality Delete 파일 (신규, bucket_0 전체에 적용)
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

버킷당 파일이 1~80개로 불균형한 상태에서는
Compaction이 **DELETE 성능 유지의 핵심**입니다.
버킷당 파일을 1~2개로 줄여야 Delete 파일 merge 비용이 관리 가능한 수준이 됩니다.

### 주기 설계 (단일 전략)

| 항목 | 값 |
|---|---|
| 대상 | 전체 파티션 (구분 없음) |
| 주기 | **4시간** |
| delete-file-threshold | **3** (버킷당 파일 80개 환경에서는 더 낮게) |
| 목표 파일 크기 | 256 MB |

> delete-file-threshold를 5 → **3**으로 낮춘 이유:
> 버킷당 파일 80개 × Delete 파일 3개 = 240번 merge → 이미 쿼리 부담 큼
> 더 이상 쌓이기 전에 Compaction 트리거

### rewrite_data_files (Delete 파일 흡수 + 파일 수 정리)

```sql
CALL catalog.system.rewrite_data_files(
  table => 'db.table',
  strategy => 'binpack',
  options => map(
    'delete-file-threshold',              '3',
    'target-file-size-bytes',             '268435456',   -- 256 MB
    'min-file-size-bytes',                '134217728',   -- 128 MB
    'max-file-size-bytes',                '402653184',   -- 384 MB
    'max-concurrent-file-group-rewrites', '10'
  ),
  where => 'dt = current_date'
);
```

### rewrite_manifests (Manifest 조각 정리)

버킷당 파일이 많을수록 Manifest 조각화가 심해집니다.
rewrite_data_files 이후 반드시 실행합니다.

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

### remove_orphan_files (참조 끊긴 파일 정리)

```sql
CALL catalog.system.remove_orphan_files(
  table      => 'db.table',
  older_than => now() - INTERVAL 5 DAYS
);
```

### 전체 실행 순서

```
1. rewrite_data_files  → Delete 파일 흡수, 버킷당 파일 수 1~2개로 정리 (4시간 주기)
2. rewrite_manifests   → Manifest 조각 정리, planning latency 감소    (4시간 주기)
3. expire_snapshots    → 오래된 Snapshot + Manifest 제거              (일 1회 새벽)
4. remove_orphan_files → 참조 끊긴 파일 정리                          (주 1회)
```

---

## 모니터링 쿼리

### 버킷별 파일 수 분포 (1~80 편차 확인)

```sql
SELECT
  partition,
  COUNT(*)                                               AS total_files,
  round(avg(file_size_in_bytes) / 1024 / 1024, 1)       AS avg_mb,
  max(file_size_in_bytes / 1024 / 1024)                  AS max_mb,
  min(file_size_in_bytes / 1024 / 1024)                  AS min_mb
FROM catalog.db.table.files
WHERE content = 0   -- 0 = data file
  AND partition.dt = current_date
GROUP BY partition
ORDER BY total_files DESC;
```

### Delete 파일 현황

```sql
SELECT
  partition,
  COUNT(*)          AS delete_file_count,
  SUM(record_count) AS delete_records
FROM catalog.db.table.files
WHERE content = 1   -- 1 = equality delete file
  AND partition.dt = current_date
GROUP BY partition
ORDER BY delete_file_count DESC
LIMIT 20;
```

### Snapshot / Manifest 누적 현황

```sql
-- 스냅샷 수
SELECT COUNT(*) AS snapshot_count
FROM catalog.db.table.snapshots;

-- Manifest 파일 수
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

[현재 상태 — 버킷당 파일 1~80개 불균형]
  버킷당 파일 80개 × Delete 파일 1개 = 읽기 시 80번 merge 연산
  → Compaction으로 버킷당 파일 1~2개로 유지하는 것이 최우선

[파일 생성 규모 (1일 기준, 현실적 추정)]
  데이터 파일 (Delete)  : ~1,440개
  Snapshot             :   144개
  Manifest             :  ~254개 (데이터 + Delete 포함)
  합계                  : ~1,838개/일

[Compaction 스케줄 — 단일 전략]
  주기             : 4시간
  대상             : 전체 파티션 (dt = current_date)
  threshold        : delete-file-threshold = 3  ← 버킷당 파일 많아서 낮게 설정
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
