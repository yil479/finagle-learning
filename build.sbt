ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.travelmsg"

val finatraVersion = "24.2.0"

lazy val root = (project in file("."))
  .settings(
    name := "travel-message-decisioning",
    // Non-exhaustive `match` on a sealed trait is a warning by default in
    // 2.13, not an error - this promotes just that category to a build
    // failure. Narrower than -Xfatal-warnings so an unrelated warning
    // (unused import, deprecation, etc.) won't block compilation while
    // you're still learning the language.
    scalacOptions += "-Wconf:cat=other-match-analysis:error",
    libraryDependencies ++= Seq(
      "com.twitter" %% "finatra-http-server" % finatraVersion,
      "com.twitter" %% "finatra-jackson"      % finatraVersion,
      "com.twitter" %% "inject-server"        % finatraVersion,
      "com.twitter" %% "inject-app"           % finatraVersion,
      "com.twitter" %% "inject-core"          % finatraVersion,
      "com.twitter" %% "inject-modules"       % finatraVersion,
      "ch.qos.logback" % "logback-classic"    % "1.5.6",
      "software.amazon.awssdk" % "dynamodb"   % "2.46.7",
      "software.amazon.awssdk" % "ses"        % "2.46.7",
      "software.amazon.awssdk" % "sns"        % "2.46.7",

      // test scopes
      "com.twitter" %% "finatra-http-server" % finatraVersion % Test classifier "tests",
      "com.twitter" %% "inject-server"       % finatraVersion % Test classifier "tests",
      "com.twitter" %% "inject-app"          % finatraVersion % Test classifier "tests",
      "com.twitter" %% "inject-core"         % finatraVersion % Test classifier "tests",
      "com.twitter" %% "inject-modules"      % finatraVersion % Test classifier "tests",
      "org.scalatest" %% "scalatest"         % "3.2.19"       % Test
    ),
    Test / fork := true,
    Test / testForkedParallel := false,
    Test / parallelExecution := false
  )
