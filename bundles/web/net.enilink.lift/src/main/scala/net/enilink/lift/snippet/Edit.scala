package net.enilink.lift.snippet

import scala.xml.NodeSeq

import net.liftweb.http.DispatchSnippet
import net.liftweb.http.JsonHandler
import net.liftweb.http.SessionVar
import net.liftweb.http.js.JE.Call
import net.liftweb.http.js.JE.JsObj
import net.liftweb.http.js.JE.JsVar
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds.Function
import net.liftweb.http.js.JsCmds.Noop
import net.liftweb.http.js.JsCmds.Script
import net.liftweb.http.js.JsCmds.jsExpToJsCmd
import net.liftweb.http.js.JsExp.strToJsExp
import net.liftweb.util.JsonCmd

// extract a String  
object XString {
  def unapply(in: Any): Option[String] = in match {
    case s: String => Some(s)
    case _ => None
  }
}

object JsonCallHandler extends SessionVar[JsonHandler](
  new JsonHandler {
    def apply(in: Any): JsCmd = in match {
      case JsonCmd("noParam", resp, _, _) =>
        Call(resp)
      case JsonCmd("deleteTriples", resp, XString(s), _) =>
        Call(resp, s)
      case _ => Noop
    }
  })

object Edit extends DispatchSnippet {
  val dispatch = Map("render" -> buildFuncs _)

  def buildFuncs(in: NodeSeq): NodeSeq =
    Script(JsonCallHandler.is.jsCmd &
      Function("noParam", List("callback"),
        JsonCallHandler.is.call("noParam",
          JsVar("callback"),
          JsObj()))
        & Function("deleteTriples", List("callback", "str"),
          JsonCallHandler.is.call("deleteTriples",
            JsVar("callback"),
            JsVar("str"))))
}