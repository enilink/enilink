package net.enilink.lift.util

import net.liftweb.http.S
import net.liftweb.http.js.JsCommands
import net.liftweb.http.LiftRules
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds._
import scala.xml.NodeSeq

object Fields {
  private def msgId(id: String) = "msg-" + id

  def error(id: String, n: String) {
    S.appendJs(Run("$('#" + id + "').removeClass('has-warning has-success').addClass('has-error')"))
    S.error(msgId(id), n)
  }

  def warning(id: String, n: String) {
    S.appendJs(Run("$('#" + id + "').removeClass('has-error has-success').addClass('has-warning')"))
    S.warning(msgId(id), n)
  }

  def notice(id: String, n: String) {
    S.appendJs(Run("$('#" + id + "').removeClass('has-error has-success has-warning')"))
    S.notice(msgId(id), n)
  }

  def success(id: String) {
    S.appendJs(Run("$('#" + id + "').removeClass('has-error has-warning').addClass('has-success')"))
    S.notice(msgId(id), "")
  }
  
  def default(id: String) {
    S.appendJs(Run("$('#" + id + "').removeClass('has-error has-success has-warning')"))
    S.notice(msgId(id), "")
  }

  def msgBox(id: String): NodeSeq = <div data-lift={ "bs.feedback?id=" + msgId(id) } class="form-control-feedback"></div>
}