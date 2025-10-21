val zioSbtVersion   = "0.4.0-alpha.35"
addSbtPlugin("dev.zio" % "zio-sbt-website"   % zioSbtVersion)
addSbtPlugin("dev.zio" % "zio-sbt-ci"        % zioSbtVersion exclude ("org.xerial.sbt", "sbt-sonatype"))
addSbtPlugin("dev.zio" % "zio-sbt-ecosystem" % zioSbtVersion)

addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.9")

addSbtPlugin("org.typelevel" % "sbt-tpolecat" % "0.5.2")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.5")

addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.4")

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.0")

addSbtPlugin("org.portable-scala" % "sbt-scala-native-crossproject" % "1.3.2")
