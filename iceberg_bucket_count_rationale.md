# Iceberg 버킷 수(16 / 32 / 64) 테스트 근거 정리

> Apache Iceberg 테이블 설계 시 버킷 수를 16 → 32 → 64로 단계적으로 테스트하는 이유와 각 근거 출처를 정리한 문서입니다.

---

## 1. 2의 거듭제곱(Power of 2) 원칙

### 내용

버킷 수는 반드시 **2의 거듭제곱(16, 32, 64, 128…)** 으로 설정해야 한다.  
이는 두 가지 이유에서 권장된다.

- **해시 분산 균일성**: Murmur3 해시 결과에 mod N 연산을 적용할 때, N이 2의 거듭제곱이면 나머지 값이 균일하게 분포된다.
- **Partition Evolution 용이성**: 향후 버킷 수를 늘리거나 줄일 때 2배/절반 단위 조정이 가능해 이전 데이터와의 호환성이 유지된다.

따라서 **16 → 32 → 64** 는 각각 `2^4 → 2^5 → 2^6`으로, 이 원칙에 따른 자연스러운 테스트 구간이다.

### 출처

| 문서 | URL |
|---|---|
| Cazpian — *Iceberg Table Design: Properties, Partitioning, and Commit Best Practices* (2026.02) | https://www.cazpian.ai/blog/iceberg-table-design-properties-partitioning-and-commit-best-practices |
| Apache Iceberg 공식 스펙 — Bucket Transform | https://iceberg.apache.org/spec/ |

---

## 2. 버킷 수 계산 공식 (데이터량 역산)

### 내용

적절한 버킷 수는 아래 공식으로 역산한다.

```
버킷 수 = 파티션당 일일 데이터량 ÷ 목표 파일 크기
          → 결과를 2의 거듭제곱으로 올림
```

### 우리 테이블 케이스 적용

| 항목 | 수치 |
|---|---|
| 일일 처리량 | ~900 GB |
| 기존 파티셔닝 | `date × code(cardinality ~15)` |
| 파티션당 예상 데이터 | ~900 GB ÷ 15 ≈ **60 GB** |
| 목표 파일 크기 | 256 ~ 512 MB |
| 역산 버킷 수 | 60 GB ÷ 512 MB ≈ 117 |
| 2의 거듭제곱 후보 | **64(under) / 128(over) 경계** |

즉 16·32·64는 **"부족 → 적정 → 한계"** 구간을 커버하는 실험 범위이다.

### 출처

| 문서 | URL |
|---|---|
| Cazpian — *Iceberg Table Design: Properties, Partitioning, and Commit Best Practices* (2026.02) | https://www.cazpian.ai/blog/iceberg-table-design-properties-partitioning-and-commit-best-practices |

---

## 3. Iceberg 공식 스펙 — Murmur3 해시 기반 분산

### 내용

Iceberg 버킷 transform의 내부 동작:

```
bucket_N(x) = (murmur3_x86_32_hash(x) & Integer.MAX_VALUE) % N
```

- 해시 알고리즘: **32-bit Murmur3** (x86 variant, seed=0)
- N이 2의 거듭제곱일 때 `% N` 연산 결과의 균일 분산이 보장됨
- 데이터 증가에 따른 버킷 수 변경은 **partition spec evolution** 을 통해 지원

### 출처

| 문서 | URL |
|---|---|
| Apache Iceberg 공식 스펙 — Bucket Transform Details | https://iceberg.apache.org/spec/ |

---

## 4. Over-bucketing 경고 — 소파일 문제 방지

### 내용

버킷 수가 지나치게 많으면 오히려 성능이 저하된다.

> "Over-bucketing은 under-bucketing보다 더 나쁘다.  
> 일일 데이터가 10GB인데 `bucket(1024, col)`을 쓰면 버킷당 ~10MB에 불과해  
> 수천 개의 undersized 파일이 매일 생성된다."

- 소파일(< 128MB)이 많아지면 메타데이터 overhead 및 query planning latency 증가
- **작은 수(16)부터 시작해 점진적으로 늘려가는 설계**가 이 경고를 반영한 것

### 출처

| 문서 | URL |
|---|---|
| Cazpian — *Iceberg Table Design: Properties, Partitioning, and Commit Best Practices* (2026.02) | https://www.cazpian.ai/blog/iceberg-table-design-properties-partitioning-and-commit-best-practices |
| Dremio — *Compaction in Apache Iceberg* | https://www.dremio.com/blog/compaction-in-apache-iceberg-fine-tuning-your-iceberg-tables-data-files/ |

---

## 5. Partition Evolution — 운영 중 버킷 수 변경 가능

### 내용

테스트 결과에 따라 운영 테이블의 버킷 수를 **무중단으로** 변경할 수 있다.

- partition spec을 변경해도 **이전 데이터는 그대로 유지**됨
- 새 데이터만 새 spec으로 기록
- 쿼리 시 각 partition layout이 독립적으로 plan되는 **split planning** 적용
- 기존 쿼리 수정 불필요

```sql
-- 버킷 수 변경 예시 (32 → 64)
ALTER TABLE my_catalog.db.my_table
DROP PARTITION FIELD bucket(32, bucket_col);

ALTER TABLE my_catalog.db.my_table
ADD PARTITION FIELD bucket(64, bucket_col);
```

### 출처

| 문서 | URL |
|---|---|
| Apache Iceberg 공식 문서 — Evolution | https://iceberg.apache.org/docs/latest/evolution/ |

---

## 6. 파일 크기 가이드라인 — 테스트 평가 기준점

### 내용

각 버킷 수 테스트에서 **파티션당 생성 파일 크기**가 아래 범위에 드는지가 핵심 판단 지표다.

| 파일 크기 | 상태 |
|---|---|
| < 128 MB | ❌ 소파일 — 메타데이터 overhead, planning latency 증가 |
| 128 MB ~ 256 MB | ⚠️ 허용 범위이나 최적 미달 |
| **256 MB ~ 512 MB** | ✅ **권장 범위** |
| > 1 GB | ⚠️ 파일 하나가 너무 넓은 value range → pruning 효율 저하 |

- Spark write target: **512 MB**
- Compaction (rewrite_data_files) target: **256 MB**

### 출처

| 문서 | URL |
|---|---|
| Dremio — *Compaction in Apache Iceberg* | https://www.dremio.com/blog/compaction-in-apache-iceberg-fine-tuning-your-iceberg-tables-data-files/ |
| AWS Prescriptive Guidance — *Optimizing read performance* | https://docs.aws.amazon.com/prescriptive-guidance/latest/apache-iceberg-on-aws/best-practices-read.html |

---

## 종합 요약

| # | 근거 | 핵심 내용 | 출처 |
|---|---|---|---|
| 1 | 2의 거듭제곱 원칙 | 해시 균일 분산 + evolution 용이 | Cazpian (2026), Iceberg Spec |
| 2 | 버킷 수 계산 공식 | 데이터량 ÷ 목표 파일 크기 역산 → 16/32/64가 우리 규모의 테스트 구간 | Cazpian (2026) |
| 3 | Murmur3 해시 스펙 | N이 2의 거듭제곱일 때 mod 분산 최적 | Iceberg 공식 스펙 |
| 4 | Over-bucketing 경고 | 소파일 문제 방지 → 작은 수부터 시작 | Cazpian (2026), Dremio |
| 5 | Partition Evolution | 운영 중 버킷 수 변경 가능 → 단계 테스트 의미 있음 | Iceberg 공식 문서 |
| 6 | 파일 크기 기준 | 256–512 MB 범위가 테스트 판단 기준 | Dremio, AWS |

---

*작성일: 2026-03-27*  
*환경: Apache Spark 3.4.3 / Apache Iceberg / MinIO / Kubernetes (spark-operator)*
