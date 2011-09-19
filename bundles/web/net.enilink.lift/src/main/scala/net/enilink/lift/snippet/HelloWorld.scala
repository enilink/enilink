package net.enilink.lift.snippet

import java.util.Date
import net.enilink.lift.lib.DependencyFactory
import net.liftweb.common._
import net.liftweb.util.Helpers._
import net.liftweb.util._
import net.enilink.lift.eclipse.SelectionHolder

class HelloWorld {
  lazy val date: Box[Date] = DependencyFactory.inject[Date] // inject the date

  // replace the contents of the element with id "time" with the date
  //  def howdy = "#time *" #> date.map(_.toString)

  def howdy = "#time *" #> ("" + SelectionHolder.getSelection())
}

