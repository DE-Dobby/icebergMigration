ThisBuild / scalaVersion := "2.12.17"
ThisBuild / organization := "com.migration"
ThisBuild / version      := "1.0.0"

lazy val root = (project in file("."))
  .settings(
    name := "iceberg-migration",

    libraryDependencies ++= Seq(
      "org.apache.spark"  %% "spark-core"          % "3.4.1" % "provided",
      "org.apache.spark"  %% "spark-sql"           % "3.4.1" % "provided",
      "org.apache.iceberg" % "iceberg-spark-runtime-3.4_2.12" % "1.10.0",
      "org.apache.logging.log4j" % "log4j-core"   % "2.20.0" % "provided",
      "org.apache.logging.log4j" % "log4j-api"    % "2.20.0" % "provided"
    ),

    assembly / assemblyJarName := s"${name.value}-${version.value}.jar",

    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
      case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
      case "reference.conf"                           => MergeStrategy.concat
      case x                                          => MergeStrategy.first
    },

    // Spark and Hadoop provided at runtime
    assembly / assemblyOption := (assembly / assemblyOption).value
      .withIncludeScala(false)
  )
