name := "net.enilink.lift"
 
scalaVersion := "2.10.0"
 
resolvers ++= Seq(
	"snapshots" at "http://oss.sonatype.org/content/repositories/snapshots",
	"staging" at "http://oss.sonatype.org/content/repositories/staging",
	"releases" at "http://oss.sonatype.org/content/repositories/releases"
)

retrieveManaged := true

libraryDependencies ++= {
  val liftVersion = "2.5.1" // Put the current/latest lift version here
  Seq(
    "net.liftweb" %% "lift-webkit" % liftVersion % "compile",
    "net.liftweb" %% "lift-wizard" % liftVersion % "compile",
    "net.liftmodules" %% "lift-jquery-module_2.5" % "2.4"
  )
}

// Customize any further dependencies as desired
libraryDependencies ++= Seq(
  "org.scala-tools.testing" % "specs_2.10" % "1.6.9" % "test" // For specs.org tests
)
