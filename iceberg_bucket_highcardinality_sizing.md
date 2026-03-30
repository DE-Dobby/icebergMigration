# Iceberg 버킷 수 산정 — 고카디널리티 파티션 130 ~ 200 GB 기준

> 버킷 컬럼 카디널리티가 높은 파티션의 데이터 규모가 **130 ~ 200 GB** 임을 확인하고,
> 목표 파일 크기 256 MB를 기준으로 최적 버킷 수를 도출한 문서입니다.
>
> 참고: Cazpian — *Iceberg Table Design: Properties, Partitioning, and Commit Best Practices*
> https://www.cazpian.ai/blog/iceberg-table-design-properties-partitioning-and-commit-best-practices

---

## 1. 전제 조건

| 항목 | 값 |
|---|---|
| 고카디널리티 파티션 데이터량 | **130 ~ 200 GB** |
| 버킷 컬럼 최대 카디널리티 | **277** |
| 목표 파일 크기 | **256 MB** |
| 파일 크기 허용 하한 | 128 MB |
| 버킷 수 제약 | 2의 거듭제곱, 카디널리티(277) 이하가 실효 상한 |

---

## 2. 버킷 수 계산 공식 (Cazpian 공식 적용)

```
버킷 수 = 파티션 데이터량 ÷ 목표 파일 크기
          → 결과를 2의 거듭제곱으로 올림
          → 단, 카디널리티를 초과할 수 없음
```

### 130 GB 파티션

```
130 GB ÷ 256 MB = 130 × 1024 ÷ 256 = 520
→ 2의 거듭제곱 올림: 2^9 = 512
→ 카디널리티 상한 적용: min(512, 277) = 277
→ 실용 2의 거듭제곱: 256 (2^8)

결론: bucket(256) 권장
실제 파일 크기: 130 GB ÷ 256 = 520 MB  ← 목표 초과, Compaction 필요
```

### 200 GB 파티션

```
200 GB ÷ 256 MB = 200 × 1024 ÷ 256 = 800
→ 2의 거듭제곱 올림: 2^10 = 1024
→ 카디널리티 상한 적용: min(1024, 277) = 277
→ 실용 2의 거듭제곱: 256 (2^8)

결론: bucket(256) 권장
실제 파일 크기: 200 GB ÷ 256 = 800 MB  ← 목표 초과, Compaction 필요
```

> **핵심 결론**: 130~200 GB 파티션에서 256 MB 목표를 **Write 단계에서 달성하려면
> bucket(512 ~ 1024)이 필요하지만, 카디널리티 상한(277)으로 인해 불가능합니다.**
> 실용적 상한인 **bucket(256)** 을 사용하고, Write 후 **Compaction 필수**입니다.

---

## 3. 버킷 수별 파일 크기 시뮬레이션

| 버킷 수 | 실효 버킷 수 (카디널리티 277 기준) | 130 GB 파일 크기 | 200 GB 파일 크기 | 판정 |
|---|---|---|---|---|
| **16** | 16 | 8,320 MB | 12,800 MB | ❌ 심각 |
| **32** | 32 | 4,160 MB | 6,400 MB | ❌ 심각 |
| **64** | 64 | 2,080 MB | 3,200 MB | ❌ 심각 |
| **128** | 128 | 1,040 MB | 1,600 MB | ❌ 과대 |
| **256** | 256 | **520 MB** | **800 MB** | ⚠️ 초과, Compaction 필요 |
| *(512)* | min(512,277)=277 | **482 MB** | **740 MB** | ⚠️ 카디널리티 벽 |
| *(1024)* | min(1024,277)=277 | **482 MB** | **740 MB** | ⚠️ 카디널리티 벽 |

> bucket(512) 이상으로 늘려도 카디널리티(277)에 막혀 효과 없음.
> **bucket(256)이 카디널리티 한계 내 최선.**

### 파일 크기 판정 기준

| 파일 크기 | 상태 |
|---|---|
| < 64 MB | ❌ 소파일 — planning latency 급증 |
| 64 ~ 128 MB | ⚠️ 허용 미만 — Compaction 병합 권장 |
| **128 ~ 256 MB** | ✅ 권장 범위 |
| 256 ~ 512 MB | ⚠️ 목표 초과 — Compaction 분할 필요 |
| > 512 MB | ❌ 과대 — OOM 위험, pruning 효율 저하 |

---

## 4. 카디널리티 상한이 만드는 구조적 한계

```
130~200 GB 파티션에서 256 MB를 Write 단계에서 달성하려면

필요 버킷 수 = 130 GB ÷ 256 MB = 520  →  bucket(512)
             = 200 GB ÷ 256 MB = 800  →  bucket(1024)

그러나 카디널리티 = 277
→ 실효 버킷 수 상한 = 277
→ 어떤 버킷 수를 설정해도 파일 크기 하한 = 130GB ÷ 277 ≈ 482 MB

즉, Write 단계에서 256 MB 달성은 구조적으로 불가능
→ Compaction이 필수 운영 요소
```

### 카디널리티를 높이는 것이 불가능한 경우의 대안

| 접근 방법 | 내용 | 비고 |
|---|---|---|
| **Compaction (권장)** | Write 후 256 MB로 재구성 | 운영 비용 발생 |
| 파티션 세분화 | `DT × 특정코드 × 추가컬럼` 으로 파티션 분할 | 파티션 수 증가 |
| Sort Order 추가 | 버킷 내 정렬로 읽기 효율 보완 | 파일 크기 직접 제어 안됨 |

---

## 5. bucket(256) 채택 시 운영 계획

### Write 후 파일 상태

| 파티션 데이터량 | bucket(256) 파일 크기 | 목표 대비 | 조치 |
|---|---|---|---|
| 130 GB | 520 MB | 2배 초과 | Compaction → 130GB/256MB ≈ **507개 파일** |
| 160 GB | 640 MB | 2.5배 초과 | Compaction → 160GB/256MB ≈ **625개 파일** |
| 200 GB | 800 MB | 3배 초과 | Compaction → 200GB/256MB ≈ **781개 파일** |

> Compaction 후 버킷당 파일 수 = 총 파일 수 ÷ 256 ≈ **2 ~ 3개/버킷**
> 이는 허용 범위(2~3개)이므로 Bucket Join 최적화 일부 유지, pruning 유효

### Compaction 설정 (bucket(256) 기준)

```sql
CALL system.rewrite_data_files(
  table => 'my_catalog.db.my_table',
  strategy => 'binpack',
  options => map(
    'target-file-size-bytes', '268435456',    -- 256 MB
    'min-file-size-bytes',    '134217728',    -- 128 MB
    'max-file-size-bytes',    '402653184',    -- 384 MB (이상이면 분할)
    'max-concurrent-file-group-rewrites', '20'
  ),
  where => "dt = '2026-01-01' AND 특정코드 IN ('고카디널리티_코드들')"
);
```

### Write 설정 (bucket(256) 기준)

```sql
ALTER TABLE my_catalog.db.my_table
SET TBLPROPERTIES (
  'write.distribution-mode'            = 'hash',
  'write.target-file-size-bytes'       = '268435456',   -- 256 MB
  'write.parquet.row-group-size-bytes' = '67108864'     -- 64 MB
);
```

```python
# Spark write 시 repartition
df.repartition(256, F.col("버킷컬럼")) \
  .writeTo("my_catalog.db.my_table") \
  .append()
```

---

## 6. 테스트 범위(16/32/64) vs 권장값(256) 비교

| 항목 | bucket(64) | bucket(128) | **bucket(256)** |
|---|---|---|---|
| 130 GB 파일 크기 | 2,080 MB ❌ | 1,040 MB ❌ | **520 MB ⚠️** |
| 200 GB 파일 크기 | 3,200 MB ❌ | 1,600 MB ❌ | **800 MB ⚠️** |
| Compaction 배수 (→256 MB) | 8 ~ 12배 분할 | 4 ~ 6배 분할 | **2 ~ 3배 분할** |
| 카디널리티(277) 활용도 | 23% | 46% | **93%** |
| Manifest 부담 | 낮음 ✅ | 중간 ⚠️ | 높음 ⚠️ |
| 30일 누적 파일 수 (Compaction 후) | ~28,800개 | ~57,600개 | ~115,200개 |

> **테스트 범위(16/32/64)는 130~200 GB 파티션에 대해 모두 부적합.**
> 실제 운영을 위해 **bucket(256) 추가 테스트 필요.**

---

## 7. Partition Evolution으로 단계적 전환

현재 bucket(64) → bucket(256)으로 바로 전환 대신 단계적으로 진행 가능합니다.

```sql
-- Step 1: bucket(64) → bucket(128) 전환
ALTER TABLE my_catalog.db.my_table
  DROP PARTITION FIELD bucket(64, 버킷컬럼);
ALTER TABLE my_catalog.db.my_table
  ADD PARTITION FIELD bucket(128, 버킷컬럼);

-- 효과 확인 후: bucket(128) → bucket(256) 전환
ALTER TABLE my_catalog.db.my_table
  DROP PARTITION FIELD bucket(128, 버킷컬럼);
ALTER TABLE my_catalog.db.my_table
  ADD PARTITION FIELD bucket(256, 버킷컬럼);
```

> - 이전 데이터는 기존 spec 그대로 유지
> - 새 데이터만 새 spec으로 기록
> - 쿼리 수정 불필요 (Iceberg가 split planning 자동 처리)

---

## 8. 최종 권장 요약

### 버킷 수

| 상황 | 권장 버킷 수 | 이유 |
|---|---|---|
| 현재 테스트 범위 내 최선 | **64** | 다른 선택지 없음, 단 Compaction 필수 |
| **130~200 GB 파티션 최적** | **256** | 카디널리티(277) 활용도 93%, 최소 Compaction |
| 256 달성 불가 시 차선 | **128** | Compaction 횟수 절반으로 감소 |

### 핵심 정리

```
130~200 GB 파티션 + 카디널리티 277 환경에서

bucket(256) → Write 후 파일 520~800 MB → Compaction 후 256 MB (2~3개/버킷)
bucket(64)  → Write 후 파일 2~3 GB    → Compaction 후 256 MB (8~12개/버킷)

bucket(256)이 Compaction 비용을 1/4로 줄이고
버킷당 파일 수를 2~3개 수준으로 유지해 조회 성능 우위

카디널리티(277) 이상의 버킷은 추가 효과 없음 (실효 버킷 수 277에 수렴)
→ bucket(256)이 이 환경의 실질적 상한선
```

---

*작성일: 2026-03-30*
*환경: Apache Spark 3.4.3 / Apache Iceberg / MinIO / Kubernetes (spark-operator)*
*참고: Cazpian — Iceberg Table Design Best Practices (2026)*
*고카디널리티 파티션 데이터량: 130~200 GB | 목표 파일 크기: 256 MB | 카디널리티 상한: 277*
