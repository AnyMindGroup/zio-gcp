import scala.collection.mutable.ListBuffer
import scalanativecrossproject.NativePlatform
import sbtcrossproject.{JVMPlatform, CrossProject, CrossClasspathDependency}
import zio.sbt.githubactions.{Job, Step, ActionRef}
import scala.annotation.tailrec
import _root_.io.circe.Json

enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

def withCurlInstallStep(j: Job) = j.copy(steps = j.steps.map {
  case s: Step.SingleStep if s.name.contains("Install libuv") =>
    Step.SingleStep(
      name = "Install libuv",
      run = Some("sudo apt-get update && sudo apt-get install -y libuv1-dev libidn2-dev libcurl3-dev"),
    )
  case s => updatedBuildSetupStep(s)
})

def withBuildSetupUpdate(j: Job) = j.copy(steps = j.steps.map(updatedBuildSetupStep))

def updatedBuildSetupStep(step: Step) = step match {
  case s: Step.SingleStep if s.name.contains("Setup SBT") =>
    Step.SingleStep(
      name = "Setup build tools",
      uses = Some(ActionRef("VirtusLab/scala-cli-setup@main")),
      parameters = Map("apps" -> Json.fromString("sbt")),
    )
  case s: Step.SingleStep if s.name == "Test" =>
    s.copy(run = Some("sbt buildCodegenBin +test"))
  case s: Step.SingleStep if s.name == "Check all code compiles" =>
    s.copy(run = Some("sbt buildCodegenBin +Test/compile"))
  case s => s
}

lazy val _scala3 = "3.3.5"

lazy val _zioVersion = "2.1.16"

lazy val sttpClient4Version = "4.0.0-RC1"

lazy val jsoniterVersion = "2.33.2"

lazy val codegenVersion = "0.0.2"

inThisBuild(
  List(
    name         := "ZIO Google Cloud clients",
    zioVersion   := _zioVersion,
    organization := "com.anymindgroup",
    licenses     := Seq(License.Apache2),
    homepage     := Some(url("https://anymindgroup.com")),
    developers := List(
      Developer(id = "rolang", name = "Roman Langolf", email = "rolang@pm.me", url = url("https://github.com/rolang")),
      Developer(
        id = "dutch3883",
        name = "Panuwach Boonyasup",
        email = "dutch3883@hotmail.com",
        url = url("https://github.com/dutch3883"),
      ),
      Developer(
        id = "qhquanghuy",
        name = "Huy Nguyen",
        email = "huy_ngq@flinters.vn",
        url = url("https://github.com/qhquanghuy"),
      ),
    ),
    scala3             := _scala3,
    scalaVersion       := _scala3,
    crossScalaVersions := Seq(_scala3),
    ciEnabledBranches  := Seq("main"),
    ciTestJobs         := ciTestJobs.value.map(withCurlInstallStep),
    ciBuildJobs        := ciBuildJobs.value.map(withBuildSetupUpdate),
    ciJvmOptions ++= Seq("-Xms2G", "-Xmx2G", "-Xss4M", "-XX:+UseG1GC"),
    ciTargetJavaVersions   := Seq("21"),
    scalafmt               := true,
    scalafmtSbtCheck       := true,
    sonatypeCredentialHost := xerial.sbt.Sonatype.sonatypeCentralHost,
    ciReleaseJobs := ciReleaseJobs.value.map(j =>
      j.copy(
        steps = j.steps.map {
          case Step.SingleStep(name @ "Release", _, _, _, _, _, env) =>
            Step.SingleStep(
              name = name,
              run = Some(
                """|echo "$PGP_SECRET" | base64 -d -i - > /tmp/signing-key.gpg
                   |echo "$PGP_PASSPHRASE" | gpg --pinentry-mode loopback --passphrase-fd 0 --import /tmp/signing-key.gpg
                   |(echo "$PGP_PASSPHRASE"; echo; echo) | gpg --command-fd 0 --pinentry-mode loopback --change-passphrase $(gpg --list-secret-keys --with-colons 2> /dev/null | grep '^sec:' | cut --delimiter ':' --fields 5 | tail -n 1)
                   |sbt 'buildCodegenBin; publishSigned; sonatypeCentralRelease'""".stripMargin
              ),
              env = env,
            )
          case s => s
        }
      )
    ),
    // this overrides the default post release jobs generated by zio-sbt-ci which publish the docs to NPM Registry
    // can try to make it work with NPM later
    ciPostReleaseJobs := Nil,
    ciLintJobs := ciLintJobs.value.map(j =>
      j.copy(steps = j.steps.map {
        case s: Step.SingleStep if s.name == "Lint" => s.copy(run = Some("sbt buildCodegenBin lint"))
        case s                                      => s
      })
    ),
  )
)

lazy val commonSettings = List(
  javacOptions ++= Seq("-source", "21"),
  Compile / scalacOptions ++= Seq("-source:future", "-rewrite"),
  Compile / scalacOptions --= sys.env.get("CI").fold(Seq("-Xfatal-warnings"))(_ => Nil),
  Test / scalafixConfig := Some(new File(".scalafix_test.conf")),
  Test / scalacOptions --= Seq("-Xfatal-warnings"),
  semanticdbEnabled := true,
  semanticdbVersion := scalafixSemanticdb.revision,// use Scalafix compatible version
)

val noPublishSettings = List(
  publish         := {},
  publishLocal    := {},
  publishArtifact := false,
  publish / skip  := true,
)

lazy val gcpClientsCrossProjects: Seq[CrossProject] = for {
  (apiName, apiVersion) <- Seq("aiplatform" -> "v1", "iamcredentials" -> "v1", "pubsub" -> "v1", "storage" -> "v1")
  httpSource            <- Seq("Sttp4")
  jsonCodec             <- Seq("Jsoniter")
  arrayType             <- Seq("ZioChunk")
  name                   = s"zio-gcp-$apiName".toLowerCase()
  id                     = s"$name-$apiVersion".toLowerCase()
} yield {
  CrossProject
    .apply(id = id, base = file(name) / apiVersion)(JVMPlatform, NativePlatform)
    .settings(commonSettings)
    .settings(
      Compile / scalacOptions --= Seq("-Xfatal-warnings"),
      Compile / sourceGenerators += codegenTask(
        apiName = apiName,
        apiVersion = apiVersion,
        httpSource = httpSource,
        jsonCodec = jsonCodec,
        arrayType = arrayType,
      ),
      libraryDependencies ++= Seq(
        "com.softwaremill.sttp.client4"         %%% "core"                  % sttpClient4Version,
        "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-core"   % jsoniterVersion,
        "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-macros" % jsoniterVersion % "compile-internal",
        "dev.zio"                               %%% "zio"                   % _zioVersion,
      ),
    )
}

lazy val gcpClients: CompositeProject = new CompositeProject {
  override def componentProjects: Seq[Project] = gcpClientsCrossProjects.flatMap(_.componentProjects)
}

lazy val gcpClientsProjects: Seq[ProjectReference] = gcpClients.componentProjects.map(p => LocalProject(p.id))

lazy val cliBinFile: File = {
  val binTarget = new File("codegen/target/bin")

  def normalise(s: String) = s.toLowerCase.replaceAll("[^a-z0-9]+", "")
  val props                = sys.props.toMap
  val os = normalise(props.getOrElse("os.name", "")) match {
    case p if p.startsWith("linux")                         => "linux"
    case p if p.startsWith("windows")                       => "windows"
    case p if p.startsWith("osx") || p.startsWith("macosx") => "macosx"
    case _                                                  => "unknown"
  }

  val arch = (
    normalise(props.getOrElse("os.arch", "")),
    props.getOrElse("sun.arch.data.model", "64"),
  ) match {
    case ("amd64" | "x64" | "x8664" | "x86", bits) => s"x86_${bits}"
    case ("aarch64" | "arm64", bits)               => s"aarch$bits"
    case _                                         => "unknown"
  }

  binTarget / "bin" / s"gcp-codegen-$arch-$os"
}

lazy val buildCodegenBin = taskKey[File]("")
buildCodegenBin := {
  val built   = (codegen / Compile / nativeLinkReleaseFast).value
  val destZip = new File(s"${cliBinFile.getPath()}.zip")

  IO.copyFile(built, cliBinFile)
  cliBinFile
}

def codegenTask(
  apiName: String,
  apiVersion: String,
  httpSource: String,
  jsonCodec: String,
  arrayType: String,
) = Def.task {
  val logger        = streams.value.log
  val codegenBin    = cliBinFile
  val outDir        = (Compile / sourceManaged).value
  val targetBasePkg = s"${organization.value}.gcp.$apiName.$apiVersion"
  val outPkgDir     = outDir / targetBasePkg.split('.').mkString(java.io.File.separator)

  if (!codegenBin.exists()) {
    throw new InterruptedException(
      s"Command line binary ${codegenBin.getPath()} was not found. Run 'sbt buildCodegenBin' first."
    )
  } else {
    @tailrec
    def listFilesRec(dir: List[File], res: List[File]): List[File] =
      dir match {
        case x :: xs =>
          val (dirs, files) = IO.listFiles(x).toList.partition(_.isDirectory())
          listFilesRec(dirs ::: xs, files ::: res)
        case Nil => res
      }

    if (outPkgDir.exists() && outPkgDir.listFiles().nonEmpty) {
      logger.info(s"Skipping code generation. $apiName client sources found in ${outPkgDir.getPath()}.")
      listFilesRec(List(outPkgDir), Nil)
    } else {
      import sys.process.*

      val dialect = if (scalaVersion.value.startsWith("2")) "Scala2" else "Scala3"

      logger.info(s"Generating Google client sources")

      val errs = ListBuffer.empty[String]
      List(
        s"${codegenBin.getPath()}",
        s"-out-dir=$outDir",
        s"-out-pkg=$targetBasePkg",
        s"-specs=codegen/src/main/resources/${apiName}_${apiVersion}.json",
        s"-http-source=$httpSource",
        s"-json-codec=$jsonCodec",
        s"-array-type=$arrayType",
        s"-dialect=$dialect",
      ).mkString(" ") ! ProcessLogger(i => logger.debug(i), e => errs += e) match {
        case 0 => ()
        case c => throw new InterruptedException(s"Failure on code generation:\n${errs.mkString("\n")}")
      }

      val files = listFilesRec(List(outPkgDir), Nil)
      files.foreach(f => logger.success(s"Generated ${f.getPath}"))

      // formatting (may need to find another way...)
      logger.info(s"Formatting sources in $outDir...")
      s"scala-cli fmt --scalafmt-conf=./.scalafmt.conf $outDir" ! ProcessLogger(_ => ()) // add logs when needed
      s"rm -rf $outDir/.scala-build".!!
      logger.success("Formatting done")

      files
    }
  }
}

lazy val root =
  (project in file("."))
    .aggregate(
      zioGcpAuth.jvm,
      zioGcpAuth.native,
    )
    .aggregate(gcpClientsProjects*)
    .settings(commonSettings)
    .settings(noPublishSettings)
    .settings(
      coverageDataDir := {
        val scalaVersionMajor = scalaVersion.value.head
        target.value / s"scala-$scalaVersionMajor"
      }
    )

lazy val codegen = (project in file("codegen"))
  .settings(
    scalaVersion       := _scala3,
    crossScalaVersions := Seq(_scala3),
    libraryDependencies ++= Seq(
      "dev.rolang" %%% "gcp-codegen-cli" % codegenVersion
    ),
  )
  .enablePlugins(ScalaNativePlugin)

lazy val zioGcpAuth = crossProject(JVMPlatform, NativePlatform)
  .in(file("zio-gcp-auth"))
  .settings(moduleName := "zio-gcp-auth")
  .settings(commonSettings)
  .settings(
    scalacOptions --= List("-Wunused:nowarn"),
    libraryDependencies ++= Seq(
      "dev.zio"                               %%% "zio"                   % zioVersion.value,
      "com.softwaremill.sttp.client4"         %%% "core"                  % sttpClient4Version,
      "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-core"   % jsoniterVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %%% "jsoniter-scala-macros" % jsoniterVersion  % "compile-internal",
      "dev.zio"                               %%% "zio-test"              % zioVersion.value % Test,
      "dev.zio"                               %%% "zio-test-sbt"          % zioVersion.value % Test,
    ),
  )
  .jvmSettings(
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client4" %%% "zio" % sttpClient4Version
    )
  )
  .nativeSettings(
    Compile / scalacOptions --= Seq("-Xfatal-warnings"),
    libraryDependencies ++= Seq(
      "io.github.cquiroz" %%% "scala-java-time" % "2.6.0"
    ),
  )

lazy val examples = crossProject(JVMPlatform, NativePlatform)
  .in(file("examples"))
  .dependsOn(zioGcpAuth)
  .dependsOn(gcpClientsCrossProjects.map(p => new CrossClasspathDependency(p, p.configuration))*)
  .settings(noPublishSettings)
  .settings(
    scalaVersion       := _scala3,
    crossScalaVersions := Seq(_scala3),
    coverageEnabled    := false,
    fork               := true,
  )

lazy val tests = crossProject(JVMPlatform, NativePlatform)
  .in(file("tests"))
  .dependsOn(zioGcpAuth)
  .dependsOn(gcpClientsCrossProjects.map(p => new CrossClasspathDependency(p, p.configuration))*)
  .settings(commonSettings)
  .settings(noPublishSettings)
  .settings(
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio-test"     % zioVersion.value % Test,
      "dev.zio" %%% "zio-test-sbt" % zioVersion.value % Test,
    )
  )

lazy val docs = project
  .in(file("zio-gcp-docs"))
  .settings(
    moduleName := "zio-gcp-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    projectName                                := "Google Cloud clients for ZIO",
    mainModuleName                             := (zioGcpAuth.jvm / moduleName).value,
    projectStage                               := ProjectStage.Development,
    ScalaUnidoc / unidoc / unidocProjectFilter := inProjects(zioGcpAuth.jvm),
    readmeDocumentation                        := "",
    readmeContribution                         := "",
    readmeSupport                              := "",
    readmeLicense                              := "",
    readmeAcknowledgement                      := "",
    readmeCodeOfConduct                        := "",
    readmeCredits                              := "",
    readmeBanner                               := "",
    readmeMaintainers                          := "",
  )
  .enablePlugins(WebsitePlugin)
  .dependsOn(zioGcpAuth.jvm)
