ThisBuild / scalaVersion := "3.7.4"
ThisBuild / organization := "com.github.kmizu.pal"

val munitV = "1.1.1"

// Research scaffolding for a small PEG for PAL: a PEG interpreter, exhaustive tests against the
// palindrome oracle, real-time multitape machines and their compilation to PEG.
lazy val research = project
  .in(file("."))
  .settings(
    name := "pal-peg-research",
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all", "-Werror"),
    libraryDependencies += "org.scalameta" %% "munit" % munitV % Test,
    Test / fork := true,
    Test / javaOptions := Seq("-Xss512m", "-Xmx4g"),
    run / fork := true,
    run / javaOptions := Seq("-Xss512m", "-Xmx4g")
  )
