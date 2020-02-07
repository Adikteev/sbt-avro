package sbtavro

import java.io.File
import java.util.concurrent.atomic.AtomicReference

import org.apache.avro.compiler.idl.Idl
import org.apache.avro.compiler.specific.SpecificCompiler
import org.apache.avro.compiler.specific.SpecificCompiler.FieldVisibility
import org.apache.avro.generic.GenericData.StringType
import org.apache.avro.{Protocol, Schema}
import sbt.ConfigKey.configurationToKey
import sbt.Keys._
import sbt._

/**
 * Simple plugin for generating the Java sources for Avro schemas and protocols.
 */
object SbtAvro extends AutoPlugin {

  object autoImport {

    val AvroConfig = config("avro")

    val stringType = SettingKey[String]("string-type", "Type for representing strings. " +
      "Possible values: CharSequence, String, Utf8. Default: CharSequence.")

    val enableDecimalLogicalType = SettingKey[Boolean]("enableDecimalLogicalType",
      "Set to true to use java.math.BigDecimal instead of java.nio.ByteBuffer for logical type \"decimal\"")

    val fieldVisibility = SettingKey[String]("field-visibiliy", "Field Visibility for the properties" +
      "Possible values: private, public, public_deprecated. Default: public_deprecated.")

    val useNamespace = SettingKey[Boolean]("use-namespace", "Validate that directory layout reflects namespaces, i.e. " +
      "src/main/avro/com/myorg/MyRecord.avsc")

    val generate = TaskKey[Seq[File]]("generate", "Generate the Java sources for the Avro files.")

    lazy val avroSettings: Seq[Setting[_]] = inConfig(AvroConfig)(Seq[Setting[_]](
      sourceDirectories := Seq((sourceDirectory in Compile).value / "avro"),
      javaSource := (sourceManaged in Compile).value / "compiled_avro",
      stringType := "CharSequence",
      fieldVisibility := "public_deprecated",
      enableDecimalLogicalType := true,
      useNamespace := false,
      version := "1.9.0",

      managedClasspath := {
        Classpaths.managedJars(AvroConfig, classpathTypes.value, update.value)
      },
      generate := sourceGeneratorTask.value)
    ) ++ Seq[Setting[_]](
      sourceGenerators in Compile += (generate in AvroConfig).taskValue,
      managedSourceDirectories in Compile += (javaSource in AvroConfig).value,
      cleanFiles += (javaSource in AvroConfig).value,
      clean := {
        schemaParser.set(new Schema.Parser())
        clean.value
      },
      libraryDependencies += "org.apache.avro" % "avro" % (version in AvroConfig).value,
      ivyConfigurations += AvroConfig
    )
  }

  import autoImport._
  override def requires = sbt.plugins.JvmPlugin

  // This plugin is automatically enabled for projects which are JvmPlugin.
  override def trigger = allRequirements

  // a group of settings that are automatically added to projects.
  override val projectSettings = avroSettings

  def compileIdl(idl: File, target: File, stringType: StringType, fieldVisibility: FieldVisibility, enableDecimalLogicalType: Boolean) {
    val parser = new Idl(idl)
    val protocol = Protocol.parse(parser.CompilationUnit.toString)
    val compiler = new SpecificCompiler(protocol)
    compiler.setStringType(stringType)
    compiler.setFieldVisibility(fieldVisibility)
    compiler.setEnableDecimalLogicalType(enableDecimalLogicalType)
    compiler.compileToDestination(null, target)
  }

  val schemaParser = new AtomicReference(new Schema.Parser())

  def compileAvscs(srcDir: File, target: File, stringType: StringType, fieldVisibility: FieldVisibility, enableDecimalLogicalType: Boolean, useNamespace: Boolean) {
    import com.spotify.avro.mojo._
    val refs = (srcDir ** "*.avsc").get.map { avsc =>
      sbt.ConsoleLogger().info("Compiling Avro schemas %s".format(avsc))
      new AvroFileRef(srcDir, avsc.relativeTo(srcDir).get.toString)
    }

    val global = schemaParser.get()
    // copy of global schemaParser to avoid race condition
    val parser = new Schema.Parser()
      .addTypes(global.getTypes)
      .setValidate(global.getValidate)
      .setValidateDefaults(global.getValidateDefaults)
    val compiler = new AvscFilesCompiler(parser)
    compiler.setStringType(stringType)
    compiler.setFieldVisibility(fieldVisibility)
    compiler.setUseNamespace(useNamespace)
    compiler.setEnableDecimalLogicalType(enableDecimalLogicalType)
    compiler.setCreateSetters(true)
    compiler.setLogCompileExceptions(true)
    compiler.setTemplateDirectory("/org/apache/avro/compiler/specific/templates/java/classic/")

    import scala.collection.JavaConverters._
    compiler.compileFiles(refs.toSet.asJava, target)
  }

  def compileAvpr(avpr: File, target: File, stringType: StringType, fieldVisibility: FieldVisibility, enableDecimalLogicalType: Boolean) {
    val protocol = Protocol.parse(avpr)
    val compiler = new SpecificCompiler(protocol)
    compiler.setStringType(stringType)
    compiler.setFieldVisibility(fieldVisibility)
    compiler.setEnableDecimalLogicalType(enableDecimalLogicalType)
    compiler.compileToDestination(null, target)
  }

  private[this] def compile(srcDir: File, target: File, log: Logger, stringTypeName: String, fieldVisibilityName: String, enableDecimalLogicalType: Boolean, useNamespace: Boolean): Set[File] = {
    val stringType = StringType.valueOf(stringTypeName)
    val fieldVisibility = SpecificCompiler.FieldVisibility.valueOf(fieldVisibilityName.toUpperCase)
    log.info("Avro compiler using stringType=%s".format(stringType))

    for (idl <- (srcDir ** "*.avdl").get) {
      log.info("Compiling Avro IDL %s".format(idl))
      compileIdl(idl, target, stringType, fieldVisibility, enableDecimalLogicalType)
    }

    compileAvscs(srcDir, target, stringType, fieldVisibility, enableDecimalLogicalType, useNamespace)

    for (avpr <- (srcDir ** "*.avpr").get) {
      log.info("Compiling Avro protocol %s".format(avpr))
      compileAvpr(avpr, target, stringType, fieldVisibility, enableDecimalLogicalType)
    }

    (target ** "*.java").get.toSet
  }

  private def sourceGeneratorTask = Def.task {
    val out = streams.value
    val srcDirs = (sourceDirectories in AvroConfig).value
    val javaSrc = (javaSource in AvroConfig).value
    val strType = stringType.value
    val fieldVis = fieldVisibility.value
    val enbDecimal = enableDecimalLogicalType.value
    val useNs = useNamespace.value
    srcDirs.flatMap(srcDir => {
      val cachedCompile = FileFunction.cached(out.cacheDirectory / "avro",
      inStyle = FilesInfo.lastModified,
      outStyle = FilesInfo.exists) { (in: Set[File]) =>
        compile(srcDir, javaSrc, out.log, strType, fieldVis, enbDecimal, useNs)
      }
      
      cachedCompile((srcDir ** "*.av*").get.toSet).toSeq
    })
  }

}
