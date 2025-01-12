import scalanativecrossproject.NativePlatform
import sbtcrossproject.JVMPlatform
import sbtcrossproject.CrossProject
import zio.sbt.githubactions.{Job, Step}
import scala.annotation.tailrec

enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

def withCurlInstallStep(j: Job) = j.copy(steps = j.steps.map {
  case s: Step.SingleStep if s.name.contains("Install libuv") =>
    Step.SingleStep(
      name = "Install libuv",
      run = Some("sudo apt-get update && sudo apt-get install -y libuv1-dev libidn2-dev libcurl3-dev"),
    )
  case s => s
})

lazy val _scala3 = "3.3.4"

lazy val _zioVersion = "2.1.14"

lazy val sttpClient4Version = "4.0.0-M22"

lazy val zioJsonVersion = "0.7.3"

lazy val jsoniterVersion = "2.33.0"

inThisBuild(
  List(
    name               := "ZIO Google Cloud clients",
    zioVersion         := _zioVersion,
    organization       := "com.anymindgroup",
    licenses           := Seq(License.Apache2),
    homepage           := Some(url("https://anymindgroup.com")),
    scala3             := _scala3,
    scalaVersion       := _scala3,
    crossScalaVersions := Seq(_scala3),
    ciEnabledBranches  := Seq("main"),
    ciTestJobs         := ciTestJobs.value.map(withCurlInstallStep),
    ciJvmOptions ++= Seq("-Xms2G", "-Xmx2G", "-Xss4M", "-XX:+UseG1GC"),
    ciTargetJavaVersions := Seq("21"),
    scalafmt             := true,
    scalafmtSbtCheck     := true,
  )
)

lazy val commonSettings = List(
  libraryDependencies ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) =>
        Seq(
          compilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1"),
          compilerPlugin("org.typelevel" %% "kind-projector"     % "0.13.3" cross CrossVersion.full),
        )
      case _ => Seq()
    }
  },
  javacOptions ++= Seq("-source", "17"),
  Compile / scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => Seq("-Ymacro-annotations", "-Xsource:3")
      case _            => Seq("-source:future", "-rewrite")
    }
  },
  Compile / scalacOptions --= sys.env.get("CI").fold(Seq("-Xfatal-warnings"))(_ => Nil),
  Test / scalafixConfig := Some(new File(".scalafix_test.conf")),
  Test / scalacOptions --= Seq("-Xfatal-warnings"),
  semanticdbEnabled := true,
  semanticdbVersion := scalafixSemanticdb.revision, // use Scalafix compatible version
  credentials += {
    for {
      username <- sys.env.get("ARTIFACT_REGISTRY_USERNAME")
      apiKey   <- sys.env.get("ARTIFACT_REGISTRY_PASSWORD")
    } yield Credentials("https://asia-maven.pkg.dev", "asia-maven.pkg.dev", username, apiKey)
  }.getOrElse(Credentials(Path.userHome / ".ivy2" / ".credentials")),
)

val releaseSettings = List(
  publishTo := Some("AnyChat Maven" at "https://asia-maven.pkg.dev/anychat-staging/maven")
)

val noPublishSettings = List(
  publish         := {},
  publishLocal    := {},
  publishArtifact := false,
  publish / skip  := true,
)

lazy val gcpClientsProjects: Seq[ProjectReference] = gcpClients.componentProjects.map(p => LocalProject(p.id))
lazy val gcpClients: CompositeProject = new CompositeProject {
  override def componentProjects: Seq[Project] =
    (for {
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
        .settings(releaseSettings)
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
    }).flatMap(_.componentProjects)
}

def codegenTask(
  apiName: String,
  apiVersion: String,
  httpSource: String,
  jsonCodec: String,
  arrayType: String,
) = Def.taskDyn {
  val codegenBin = {
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

    val name = s"gcp-codegen-$arch-$os"

    val codegenBin = binTarget / "bin" / name

    val built = (codegen / Compile / nativeLinkReleaseFast).value

    IO.copyFile(built, codegenBin)
    sLog.value.info(s"Built codegen binary in $codegenBin")

    codegenBin
  }

  Def.task {
    val logger        = streams.value.log
    val outDir        = (Compile / sourceManaged).value
    val targetBasePkg = s"${organization.value}.gcp.$apiName.$apiVersion"
    val outPkgDir     = outDir / targetBasePkg.split('.').mkString(java.io.File.separator)

    if (!codegenBin.exists()) {
      logger.error(s"Command line binary ${codegenBin.getPath()} was not found. Run 'sbt buildCliBinary' first.")
      List.empty[File]
    } else {
      @tailrec
      def listFilesRec(dir: List[File], res: List[File]): List[File] =
        dir match {
          case x :: xs =>
            val (dirs, files) = IO.listFiles(x).toList.partition(_.isDirectory())
            listFilesRec(dirs ::: xs, files ::: res)
          case Nil => res
        }

      if (outPkgDir.exists()) {
        logger.info(s"Skipping code generation. Google Pubsub client sources found in ${outPkgDir.getPath()}.")
        listFilesRec(List(outPkgDir), Nil)
      } else {
        import sys.process.*

        val dialect = if (scalaVersion.value.startsWith("2")) "Scala2" else "Scala3"

        logger.info(s"Generating Google client sources")

        List(
          s"${codegenBin.getPath()}",
          s"--out-dir=$outDir",
          s"--specs=codegen/src/main/resources/${apiName}_${apiVersion}.json",
          s"--resources-pkg=$targetBasePkg.resources",
          s"--schemas-pkg=$targetBasePkg.schemas",
          s"--http-source=$httpSource",
          s"--json-codec=$jsonCodec",
          s"--array-type=$arrayType",
          s"--dialect=$dialect",
        ).mkString(" ") ! ProcessLogger(_ => ()) // add logs when needed

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
    resolvers ++= Seq(
      "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
    ),
    libraryDependencies ++= Seq(
      "dev.rolang" %%% "gcp-codegen-cli" % "0.0.0-30-39c534dc-SNAPSHOT"
    ),
  )
  .enablePlugins(ScalaNativePlugin)

lazy val zioGcpAuth = crossProject(JVMPlatform, NativePlatform)
  .in(file("zio-gcp-auth"))
  .settings(moduleName := "zio-gcp-auth")
  .settings(commonSettings)
  .settings(releaseSettings)
  .settings(
    scalacOptions --= List("-Wunused:nowarn"),
    libraryDependencies ++= Seq(
      "dev.zio"                       %%% "zio"          % zioVersion.value,
      "com.softwaremill.sttp.client4" %%% "core"         % sttpClient4Version,
      "dev.zio"                       %%% "zio-json"     % zioJsonVersion,
      "dev.zio"                       %%% "zio-test"     % zioVersion.value % Test,
      "dev.zio"                       %%% "zio-test-sbt" % zioVersion.value % Test,
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

lazy val examples = (project in file("examples"))
  .dependsOn(zioGcpAuth.jvm)
  .settings(noPublishSettings)
  .settings(
    scalaVersion       := _scala3,
    crossScalaVersions := Seq(_scala3),
    coverageEnabled    := false,
    fork               := true,
  )

lazy val docs = project
  .in(file("zio-gcp-docs"))
  .settings(
    moduleName := "zio-gcp-docs",
    scalacOptions -= "-Yno-imports",
    scalacOptions -= "-Xfatal-warnings",
    projectName                                := "Google Cloud authentication over HTTP",
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
