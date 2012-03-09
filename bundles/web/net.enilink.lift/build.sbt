name := "net.enilink.lift"
 
scalaVersion := "2.9.0-1"
 
// seq(webSettings: _*)

// If using JRebel
// jettyScanDirs := Nil

// resolvers += "Java.net Maven2 Repository" at "http://download.java.net/maven/2/"

retrieveManaged := true

libraryDependencies ++= {
  val liftVersion = "2.4" // Put the current/latest lift version here
  Seq(
    "net.liftweb" %% "lift-webkit" % liftVersion % "compile->default",
    "net.liftweb" %% "lift-wizard" % liftVersion % "compile->default")
}

// Customize any further dependencies as desired
libraryDependencies ++= Seq(
  "org.scala-tools.testing" % "specs_2.9.0" % "1.6.8" % "test" // For specs.org tests
)