ThisBuild / scalaVersion := "3.7.4"
ThisBuild / organization := "com.github.kmizu.pal"

val munitV = "1.1.1"

// The window-pal generator: it prints an explicit PEG for the automaton that ../proof verifies.
lazy val pal = project
  .in(file("."))
  .settings(
    name := "pal",
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all", "-Werror"),
    libraryDependencies += "org.scalameta" %% "munit" % munitV % Test,
    Test / fork := true,
    Test / javaOptions := Seq("-Xss512m", "-Xmx8g"),
    // Expr hash-consing (Expr.share) uses a process-wide pool, so suites must not interleave.
    Test / parallelExecution := false
  )
