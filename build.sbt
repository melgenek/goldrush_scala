name := "goldrush_scala"

version := "0.1"

scalaVersion := "2.13.5"

libraryDependencies ++= List(
  "dev.zio" %% "zio" % "1.0.4-2",
  "dev.zio" %% "zio-streams" % "1.0.4-2",
  "com.softwaremill.sttp.client3" %% "core" % "3.1.6",
  "com.softwaremill.sttp.client3" %% "json-common" % "3.1.6",
  "com.softwaremill.sttp.client3" %% "httpclient-backend-zio" % "3.1.6",
  "com.softwaremill.sttp.client3" %% "prometheus-backend" % "3.1.6",
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % "2.6.4",
  "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % "2.6.4" % "compile",
  "org.slf4j" % "slf4j-simple" % "1.7.30",
  "io.prometheus" % "simpleclient_common" % "0.10.0",
  "io.prometheus" % "simpleclient_hotspot" % "0.10.0"
)


scalacOptions ++= Seq("-target:11")
javacOptions ++= Seq("-source", "11", "-target", "11")

enablePlugins(AshScriptPlugin)
//dockerBaseImage := "openjdk:8-jre-alpine"
dockerBaseImage := "adoptopenjdk/openjdk11:alpine-jre"
dockerRepository := Some("stor.highloadcup.ru")
dockerUsername := Some("rally")
packageName in Docker := "modern_dolphin"
version in Docker := "latest"

//dockerAlias := DockerAlias(Some("stor.highloadcup.ru"), Some("rally"), "modern_dolphin", None)
