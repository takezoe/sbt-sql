package xerial.sbt.sql

import java.sql.JDBCType
import java.util.Properties

import sbt.{File, IO, _}

import scala.util.{Failure, Success, Try}

case class Schema(columns: Seq[Column])
case class Column(qname: String, reader:ColumnAccess, sqlType: java.sql.JDBCType, isNullable: Boolean, elementType: java.sql.JDBCType = JDBCType.NULL)
case class GeneratorConfig(sqlDir:File, targetDir:File)

object SQLModelClassGenerator extends xerial.core.log.Logger {

  private lazy val buildProps = {
    val p = new Properties()
    val in = this.getClass.getResourceAsStream("/org/xerial/sbt/sbt-sql/build.properties")
    if (in != null) {
      try {
        p.load(in)
      }
      finally {
        in.close
      }
    }
    else {
      warn("build.properties file not found")
    }
    p
  }


  lazy val getBuildTime : Long = {
    buildProps.getProperty("build_time", System.currentTimeMillis().toString).toLong
  }
  lazy val getVersion : String = {
    buildProps.getProperty("version", "unknown")
  }
}

class SQLModelClassGenerator(jdbcConfig: JDBCConfig, log:LogSupport) {
  private val db = new JDBCClient(jdbcConfig, log)

  protected val typeMapping = SQLTypeMapping.default

  private def wrapWithLimit0(sql: String) = {
    s"""-- sbt-sql version:${SQLModelClassGenerator.getVersion}
       |SELECT * FROM (
       |${sql.trim}
       |) LIMIT 0""".stripMargin
  }

  def checkResultSchema(sql: String): Schema = {
    db.withConnection {conn =>
      db.submitQuery(conn, sql) {rs =>
        val m = rs.getMetaData
        val cols = m.getColumnCount
        val colTypes = (1 to cols).map {i =>
          val name = m.getColumnName(i)
          val qname = name match {
            case "type" => "`type`"
            case _ => name
          }
          val tpe = m.getColumnType(i)
          val jdbcType = JDBCType.valueOf(tpe)

          val reader = typeMapping(jdbcType)
          val nullable = m.isNullable(i) != 0
          Column(qname, reader, jdbcType, nullable)
        }
        Schema(colTypes.toIndexedSeq)
      }
    }
  }

  def generate(config:GeneratorConfig) : Seq[File] = {
    // Submit queries using multi-threads to minimize the waiting time
    val result = Seq.newBuilder[File]
    val buildTime = SQLModelClassGenerator.getBuildTime
    log.debug(s"SQLModelClassGenerator version:${SQLModelClassGenerator.getVersion}")

    val baseDir = file(".")

    for (sqlFile <- (config.sqlDir ** "*.sql").get.par) {
      val path = sqlFile.relativeTo(config.sqlDir).get.getPath
      val targetClassFile = config.targetDir / path.replaceAll("\\.sql$", ".scala")

      val sqlFilePath = sqlFile.relativeTo(baseDir).getOrElse(sqlFile)
      log.debug(s"Processing ${sqlFilePath}")
      val latestTimestamp = Math.max(sqlFile.lastModified(), buildTime)
      if(targetClassFile.exists()
        && targetClassFile.exists()
        && latestTimestamp <= targetClassFile.lastModified()) {
        log.debug(s"${targetClassFile.relativeTo(config.targetDir).getOrElse(targetClassFile)} is up-to-date")
      }
      else {
        val sql = IO.read(sqlFile)
        Try(SQLTemplate(sql)) match {
          case Success(template) =>
            val limit0 = wrapWithLimit0(template.populated)
            log.info(s"Checking the SQL result schema of ${sqlFilePath}")
            val schema = checkResultSchema(limit0)

            // Write SQL template without type annotation
            val scalaCode = schemaToClass(sqlFile, config.sqlDir, schema, template)
            log.info(s"Generating model class: ${targetClassFile}")
            IO.write(targetClassFile, scalaCode)
            targetClassFile.setLastModified(latestTimestamp)
          case Failure(e) =>
            log.error(s"Failed to parse ${sqlFile}: ${e.getMessage}")
            throw e
        }
      }

      synchronized {
        result += targetClassFile
      }
    }
    result.result()
  }


  def schemaToParamDef(schema:Schema) = {
    schema.columns.map {c =>
      s"${c.qname}: ${c.reader.name}"
    }
  }

  def schemaToPackerCode(schema:Schema, packerName:String = "packer") = {
    for(c <- schema.columns) {
      s"${packerName}.packXXX(${c.qname})"
    }
  }

  def schemaToClass(origFile: File, baseDir: File, schema: Schema, sqlTemplate:SQLTemplate): String = {
    val packageName = origFile.relativeTo(baseDir).map {f =>
      Option(f.getParent).map(_.replaceAll("""[\\/]""", ".")).getOrElse("")
    }.getOrElse("")
    val name = origFile.getName.replaceAll("\\.sql$", "")

    val params = schemaToParamDef(schema)

    val sqlTemplateArgs = sqlTemplate.params.map {p =>
      p.defaultValue match {
        case None => s"${p.name}:${p.functionArgType}"
        case Some(v) => s"${p.name}:${p.functionArgType} = ${p.quotedValue}"
      }
    }
    val sqlArgList = sqlTemplateArgs.mkString(", ")

    val additionalImports = sqlTemplate.imports.map(x => s"import ${x.target}").mkString("\n")
    val embeddedSQL = "\"\"\"" + sqlTemplate.sql + "\"\"\""

    val code =
      s"""/**
         | * DO NOT EDIT THIS FILE. This file is auto-generated by sbt-sql
         | */
         |package ${packageName}
         |${additionalImports}
         |
         |object ${name} {
         |  def path : String = "/${packageName.replaceAll("\\.", "/")}/${name}.sql"
         |
         |  def sql(${sqlArgList}) : String = {
         |    s${embeddedSQL}
         |  }
         |}
         |
         |case class ${name}(
         |  ${params.mkString(",\n  ")}
         |)
         |""".stripMargin

    code
  }

}
