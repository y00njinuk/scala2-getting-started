import com.twitter.scrooge.ScroogeSBT

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.12.15"

lazy val root = (project in file("."))
  .enablePlugins(ScroogeSBT)
  .settings(
    name := "scala2-getting-started",
    libraryDependencies ++= Seq(
      "com.twitter" %% "finagle-thriftmux" % "21.12.0",
      "com.twitter" %% "scrooge-core" % "21.12.0",
      "org.scalatest" %% "scalatest" % "3.2.10" % Test
    )
  )
