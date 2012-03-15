package net.enilink.lift.snippet

import java.util.Date

import net.enilink.lift.util.Globals
import net.liftweb.common.Box
import net.liftweb.util.Helpers.strToCssBindPromoter
import net.liftweb.util.IterableConst.boxString

class HelloWorld {
  lazy val date: Box[Date] = Globals.inject[Date] // inject the date

  // replace the contents of the element with id "time" with the date
  def howdy = "#time *" #> date.map(_.toString)
}