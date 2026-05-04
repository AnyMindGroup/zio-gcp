val zioSbtVersion = "0.5.1"

addSbtPlugin("dev.zio" % "zio-sbt-ci"        % zioSbtVersion)
addSbtPlugin("dev.zio" % "zio-sbt-ecosystem" % zioSbtVersion)

addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.11")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.0")

addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.6")

addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")
