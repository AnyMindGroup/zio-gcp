val zioSbtVersion = "0.8.12"

addSbtPlugin("dev.zio" % "zio-sbt-ci"        % zioSbtVersion)
addSbtPlugin("dev.zio" % "zio-sbt-ecosystem" % zioSbtVersion)

addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.13")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")

addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.2.0")

addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.4.0")
