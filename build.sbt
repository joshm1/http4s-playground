name := "http4s-playground"
version := "0.1"
scalaVersion := "2.13.2"

addCompilerPlugin(("org.typelevel" %% "kind-projector" % "0.11.0").cross(CrossVersion.full))

lazy val ovotechResolver = librarymanagement.Resolver.bintrayRepo("ovotech", "maven")

lazy val ivyLocalResolver =
  Resolver.file("local", file(Path.userHome.absolutePath + "/.ivy2/local"))(Resolver.ivyStylePatterns)
resolvers ++= Seq(ivyLocalResolver, ovotechResolver)

lazy val Http4sVersion = "0.21.0"
// lazy val OvoenergyEffectVersion = "2.4.1"
lazy val OvoenergyEffectVersion = "0.1.0-SNAPSHOT" // uses jar(s) from publishLocal

libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-blaze-client" % Http4sVersion,
  "org.http4s" %% "http4s-blaze-server" % Http4sVersion,
  "org.http4s" %% "http4s-circe" % Http4sVersion,
  "org.http4s" %% "http4s-dsl" % Http4sVersion,
  "org.tpolecat" %% "natchez-jaeger" % "0.0.11",
  "com.ovoenergy.effect" %% "natchez-datadog" % OvoenergyEffectVersion,
  "com.ovoenergy.effect" %% "natchez-http4s" % OvoenergyEffectVersion
)
