name := "sangria-playground"
description := "An example of GraphQL server written with Play and Sangria."

version := "1.0-SNAPSHOT"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  ws,
  filters,
  "org.sangria-graphql" %% "sangria" % "0.6.2",
  "org.sangria-graphql" %% "sangria-play-json" % "0.3.1"
)

resolvers += "scalaz-bintray" at "http://dl.bintray.com/scalaz/releases"
resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

routesGenerator := InjectedRoutesGenerator

lazy val root = (project in file(".")).enablePlugins(PlayScala)

herokuAppName in Compile := "aqueous-hollows-6102"
herokuConfigVars in Compile := Map(
  "JAVA_OPTS" -> "-DgaCode=UA-65759630-2 -DdefaultGraphQLUrl=http://try.sangria-graphql.org/graphql"
)


EclipseKeys.withSource := true

EclipseKeys.eclipseOutput := Some("bin")
