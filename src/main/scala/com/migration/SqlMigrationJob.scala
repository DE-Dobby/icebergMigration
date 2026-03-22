package com.migration

import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory

/**
 * Iceberg 마이그레이션 Job - Spark SQL을 인자로 받아 실행합니다.
 *
 * 사용법:
 *   spark-submit --class com.migration.SqlMigrationJob \
 *     --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
 *     --conf spark.sql.catalog.spark_catalog=org.apache.iceberg.spark.SparkSessionCatalog \
 *     --conf spark.sql.catalog.spark_catalog.type=hive \
 *     --conf spark.sql.catalog.local=org.apache.iceberg.spark.SparkCatalog \
 *     --conf spark.sql.catalog.local.type=hadoop \
 *     --conf spark.sql.catalog.local.warehouse=/path/to/warehouse \
 *     iceberg-migration-1.0.0.jar \
 *     --sql "INSERT INTO local.db.target SELECT * FROM source_table"
 *
 * 아규먼트:
 *   --sql      <sql>          실행할 Spark SQL (필수)
 *   --app-name <name>         SparkSession 앱 이름 (기본값: IcebergMigrationJob)
 */
object SqlMigrationJob {

  private val logger = LoggerFactory.getLogger(getClass)

  case class JobConfig(
    sql: String,
    appName: String = "IcebergMigrationJob"
  )

  def main(args: Array[String]): Unit = {
    parseArgs(args) match {
      case Some(config) => run(config)
      case None =>
        logger.error("아규먼트 파싱 실패. --sql 옵션이 필요합니다.")
        sys.exit(1)
    }
  }

  def run(config: JobConfig): Unit = {
    logger.info(s"앱 시작: ${config.appName}")
    logger.info(s"실행할 SQL:\n${config.sql}")

    val spark = buildSparkSession(config.appName)

    try {
      executeSql(spark, config.sql)
      logger.info("SQL 실행 완료.")
    } finally {
      spark.stop()
      logger.info("SparkSession 종료.")
    }
  }

  private def executeSql(spark: SparkSession, sql: String): Unit = {
    // 세미콜론으로 구분된 여러 SQL 문을 순서대로 실행
    val statements = sql
      .split(";")
      .map(_.trim)
      .filter(_.nonEmpty)

    logger.info(s"총 ${statements.length}개 SQL 구문 실행 시작")

    statements.zipWithIndex.foreach { case (stmt, idx) =>
      logger.info(s"[${idx + 1}/${statements.length}] 실행 중:\n$stmt")
      val df = spark.sql(stmt)

      // SELECT 계열 쿼리는 결과 건수를 로그로 남김
      if (isSelectStatement(stmt)) {
        val count = df.count()
        logger.info(s"[${idx + 1}/${statements.length}] 조회 결과: $count 건")
      } else {
        // INSERT / CREATE / ALTER 등은 실행만 트리거
        df.collect()
        logger.info(s"[${idx + 1}/${statements.length}] 완료")
      }
    }
  }

  private def isSelectStatement(sql: String): Boolean =
    sql.trim.toUpperCase.startsWith("SELECT") ||
    sql.trim.toUpperCase.startsWith("SHOW") ||
    sql.trim.toUpperCase.startsWith("DESCRIBE")

  private def buildSparkSession(appName: String): SparkSession = {
    SparkSession.builder()
      .appName(appName)
      // Iceberg 확장 및 카탈로그 설정은 spark-submit --conf 또는
      // 클러스터 spark-defaults.conf 에서 주입합니다.
      // 로컬 테스트 시에는 아래 주석을 해제하세요.
      // .master("local[*]")
      // .config("spark.sql.extensions",
      //   "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
      // .config("spark.sql.catalog.local",
      //   "org.apache.iceberg.spark.SparkCatalog")
      // .config("spark.sql.catalog.local.type", "hadoop")
      // .config("spark.sql.catalog.local.warehouse", "/tmp/iceberg-warehouse")
      .enableHiveSupport()
      .getOrCreate()
  }

  private def parseArgs(args: Array[String]): Option[JobConfig] = {
    if (args.isEmpty) {
      printUsage()
      return None
    }

    var sql: Option[String]  = None
    var appName: String      = "IcebergMigrationJob"

    val it = args.iterator
    while (it.hasNext) {
      it.next() match {
        case "--sql"      if it.hasNext => sql     = Some(it.next())
        case "--app-name" if it.hasNext => appName = it.next()
        case unknown =>
          logger.warn(s"알 수 없는 옵션: $unknown")
      }
    }

    sql match {
      case Some(s) => Some(JobConfig(sql = s, appName = appName))
      case None =>
        logger.error("--sql 옵션이 없습니다.")
        printUsage()
        None
    }
  }

  private def printUsage(): Unit = {
    println(
      """
        |사용법:
        |  spark-submit --class com.migration.SqlMigrationJob <jar> [옵션]
        |
        |옵션:
        |  --sql      <sql>    실행할 Spark SQL (필수, 세미콜론으로 여러 구문 구분 가능)
        |  --app-name <name>   SparkSession 앱 이름 (기본값: IcebergMigrationJob)
        |
        |예시:
        |  --sql "INSERT INTO local.db.target_table SELECT * FROM local.db.source_table"
        |  --sql "CREATE TABLE local.db.t USING iceberg AS SELECT * FROM hive.db.old_t"
        |""".stripMargin
    )
  }
}
