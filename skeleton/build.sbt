// PROTOTYPE — walking skeleton for tikka. Throwaway; never merged. See .wayfinder/tickets/T11-walking-skeleton.md
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature")

val tapirV = "1.13.31"
val sttpV  = "4.0.26"
val circeV = "0.14.16"
val http4sV = "0.23.37"

lazy val shared = crossProject(JVMPlatform, JSPlatform, NativePlatform)
  .crossType(CrossType.Pure)
  .in(file("shared"))
  .settings(
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-core"        % tapirV,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe"  % tapirV,
      "io.circe"                    %% "circe-core"        % circeV,
      "io.circe"                    %% "circe-parser"      % circeV,
    )
  )

lazy val daemon = project
  .in(file("daemon"))
  .dependsOn(shared.jvm)
  .settings(
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir"   %% "tapir-http4s-server" % tapirV,
      "com.softwaremill.sttp.tapir"   %% "tapir-apispec-docs"  % tapirV,
      "com.softwaremill.sttp.apispec" %% "jsonschema-circe"    % "0.11.10",
      "org.http4s"                    %% "http4s-ember-server" % http4sV,
      "org.http4s"                    %% "http4s-dsl"          % http4sV,
      "org.http4s"                    %% "http4s-circe"        % http4sV,
      "org.tpolecat"                  %% "doobie-core"         % "1.0.0-RC12",
      "org.xerial"                     % "sqlite-jdbc"         % "3.53.4.0",
      "org.slf4j"                      % "slf4j-nop"           % "2.0.17",
      "org.typelevel"                 %% "log4cats-noop"       % "2.7.1",
    ),
    fork := true,
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
      case PathList("module-info.class")                           => MergeStrategy.discard
      case x => (assembly / assemblyMergeStrategy).value(x)
    },
    Compile / resourceGenerators += Def.task {
      // Production UI: embed fullLinkJS output in the jar under /ui.
      val out  = (ui / Compile / fullLinkJSOutput).value
      val dest = (Compile / resourceManaged).value / "ui"
      IO.copyDirectory(out, dest)
      (dest ** "*").get().filter(_.isFile)
    }.taskValue,
  )

lazy val ui = project
  .in(file("ui"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(shared.js)
  .settings(
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      "com.raquo"                   %% "laminar"            % "18.0.0-M5",
      "com.softwaremill.sttp.tapir" %% "tapir-sttp-client4" % tapirV,
    ),
  )

lazy val cli = project
  .in(file("cli"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(shared.native)
  .settings(
    // swiftly's clang shadows Apple's on PATH and lacks SDK headers; use the xcrun shim.
    nativeConfig ~= (_.withClang(file("/usr/bin/clang").toPath).withClangPP(file("/usr/bin/clang++").toPath)),
    libraryDependencies ++= Seq(
      "org.typelevel"                 %% "cats-effect"        % "3.7.1",
      "com.softwaremill.sttp.tapir"   %% "tapir-sttp-client4" % tapirV,
      "com.softwaremill.sttp.client4" %% "http4s-backend"     % sttpV,
      "org.http4s"                    %% "http4s-ember-client" % http4sV,
    ),
  )
