package com.migration

import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory

object SqlMigrationJob {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    if (args.isEmpty) {
      logger.error("SQL 인자가 없습니다. 사용법: spark-submit <jar> \"<SQL>\"")
      sys.exit(1)
    }

    val sql   = args(0)
    val spark = SparkSession.builder()
      .appName("IcebergMigrationJob")
      .enableHiveSupport()
      .getOrCreate()

    try {
      spark.sql(sql).show(truncate = false)
      logger.info("SQL 실행 완료")
    } catch {
      case e: Exception =>
        logger.error(s"SQL 실행 실패: ${e.getMessage}", e)
        sys.exit(1)
    } finally {
      spark.stop()
    }
  }
}
