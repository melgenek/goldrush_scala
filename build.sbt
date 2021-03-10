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
  "io.prometheus" % "simpleclient_hotspot" % "0.10.0",
  "org.scalameta" % "svm-subs" % "101.0.0" % Compile,
  "com.lmax" % "disruptor" % "3.4.2",
  "io.github.resilience4j" % "resilience4j-ratelimiter" % "1.7.0",
  "io.github.resilience4j" % "resilience4j-retry" % "1.7.0",
  "io.github.resilience4j" % "resilience4j-reactor" % "1.7.0",
  "io.projectreactor" % "reactor-core" % "3.4.3",
  "org.jctools" % "jctools-core" % "3.3.0"
)

//mainClass := Some("goldrush.Main2")

scalacOptions ++= Seq("-target:11")
javacOptions ++= Seq("-source", "11", "-target", "11")

enablePlugins(AshScriptPlugin, GraalVMNativeImagePlugin)

dockerBaseImage := "adoptopenjdk/openjdk15:alpine-jre"
dockerRepository := Some("stor.highloadcup.ru")
dockerUsername := Some("rally")
packageName in Docker := "solid_avocet"
version in Docker := "latest"
javaOptions in Universal ++= Seq(
  "-J-Xmx1850m",
  "-J-Xms1850m",
  "-J-XX:+UseNUMA",
  "-J-XX:+UseG1GC",
  "-J-XX:+UseCompressedClassPointers",
  "-J-XX:+UseCompressedOops",
  "-J-XX:+PrintCommandLineFlags",
  "-J-XX:+UnlockExperimentalVMOptions",
  "-J-XX:+UseJVMCICompiler",
  "-J-XX:+EagerJVMCI",
  "-Dgraal.ShowConfiguration=info"
)

graalVMNativeImageOptions ++= Seq(
  "--verbose",
  "--no-server",
  "-R:MaxHeapSize=1850m",
  "-R:MinHeapSize=1850m",
  //  "--static",
  "--no-fallback",
  "--enable-https",
  "-H:+ReportExceptionStackTraces"
)
