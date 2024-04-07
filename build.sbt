import zio.sbt.githubactions.{Job, Step}
enablePlugins(ZioSbtEcosystemPlugin, ZioSbtCiPlugin)

def withCurlInstallStep(j: Job) = j.copy(steps = j.steps.map {
  case s: Step.SingleStep if s.name.contains("Install libuv") =>
    Step.SingleStep(
      name = "Install libuv",
      run = Some("sudo apt-get update && sudo apt-get install -y libuv1-dev libidn2-dev libcurl3-dev"),
    )
  case s => s
})

inThisBuild(
  List(
    name               := "ZIO Google Cloud authentication",
    zioVersion         := "2.0.21",
    organization       := "com.anymindgroup",
    licenses           := Seq(License.Apache2),
    homepage           := Some(url("https://anymindgroup.com")),
    crossScalaVersions := Seq("3.3.3", "2.13.13"),
    ciEnabledBranches  := Seq("main"),
    ciTestJobs         := ciTestJobs.value.map(withCurlInstallStep),
    ciJvmOptions ++= Seq("-Xms2G", "-Xmx2G", "-Xss4M", "-XX:+UseG1GC"),
    ciTargetJavaVersions := Seq("17", "21"),
    scalafmt             := true,
    scalafmtSbtCheck     := true,
    scalafixDependencies ++= List(
      "com.github.vovapolu" %% "scaluzzi" % "0.1.23"
    ),
  )
)

lazy val sttpClient4Version = "4.0.0-M11"

lazy val zioJsonVersion = "0.6.2"

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
  credentials += {
    for {
      username <- sys.env.get("ARTIFACT_REGISTRY_USERNAME")
      apiKey   <- sys.env.get("ARTIFACT_REGISTRY_PASSWORD")
    } yield Credentials("https://asia-maven.pkg.dev", "asia-maven.pkg.dev", username, apiKey)
  }.getOrElse(Credentials(Path.userHome / ".ivy2" / ".credentials")),
) ++ scalafixSettings

val releaseSettings = List(
  publishTo := Some("AnyChat Maven" at "https://asia-maven.pkg.dev/anychat-staging/maven")
)

val noPublishSettings = List(
  publish         := {},
  publishLocal    := {},
  publishArtifact := false,
  publish / skip  := true,
)

lazy val root =
  (project in file("."))
    .aggregate(
      zioGcpAuth.jvm,
      zioGcpAuth.native,
    )
    .settings(commonSettings)
    .settings(noPublishSettings)
    .settings(
      coverageDataDir := {
        val scalaVersionMajor = scalaVersion.value.head
        target.value / s"scala-$scalaVersionMajor"
      }
    )

lazy val zioGcpAuth = crossProject(JVMPlatform, NativePlatform)
  .in(file("zio-gc-auth"))
  .settings(moduleName := "zio-gc-auth")
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
    libraryDependencies ++= Seq(
      "io.github.cquiroz" %%% "scala-java-time" % "2.5.0"
    )
  )

lazy val docs = project
  .in(file("zio-gc-auth-docs"))
  .settings(
    moduleName := "zio-gc-auth-docs",
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
