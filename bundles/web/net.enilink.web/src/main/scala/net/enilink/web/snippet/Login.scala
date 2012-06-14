package net.enilink.web.snippet

import java.io.IOException

import scala.Array.canBuildFrom
import scala.collection._
import scala.collection.JavaConversions.mapAsJavaMap
import scala.xml.Node
import scala.xml.NodeSeq.seqToNodeSeq
import scala.xml.Text

import org.eclipse.core.runtime.Platform
import org.eclipse.equinox.security.auth.LoginContextFactory

import net.enilink.rap.security.callbacks.RealmCallback
import net.enilink.rap.security.callbacks.RedirectCallback
import net.enilink.rap.security.callbacks.ResponseCallback
import javax.security.auth.Subject
import javax.security.auth.callback.Callback
import javax.security.auth.callback.CallbackHandler
import javax.security.auth.callback.NameCallback
import javax.security.auth.callback.PasswordCallback
import javax.security.auth.callback.TextInputCallback
import javax.security.auth.callback.TextOutputCallback
import javax.security.auth.login.LoginException
import net.liftweb.common.Box
import net.liftweb.common.Box.box2Option
import net.liftweb.common.Empty
import net.liftweb.common.Full
import net.liftweb.http.S
import net.liftweb.http.SHtml
import net.liftweb.http.js.JsCmds._
import net.liftweb.util.ClearClearable
import net.liftweb.util.Helpers.strToCssBindPromoter

object Login {
  val SUBJECT_KEY = "javax.security.auth.subject";
  val JAAS_CONFIG_FILE = "/resources/jaas.conf";
  val REQUIRE_LOGIN = false || "true"
    .equalsIgnoreCase(System.getProperty("enilink.loginrequired"))

  def value(name: String) = S.param(name) match {
    case Full(value) if value.length > 0 => Full(value)
    case _ => Empty
  }

  def render = {
    var form: Seq[Node] = Nil
    var buttons: Seq[Node] = <button class="btn btn-primary" type="submit">Sign in</button>

    def hidden(name: String, value: String) { form ++= <input type="hidden" name={ name } value={ value }/> }
    def handleUserCallback(cb: Callback, field: String, hide: Boolean) = {
      var vbox = value(field)
      vbox map { v =>
        cb match {
          case cb: TextInputCallback => cb.setText(v)
          case cb: NameCallback => cb.setName(v)
          case cb: PasswordCallback => cb.setPassword(v.toCharArray)
        }
      }
      val (prompt, placeholder, inputType) = cb match {
        case cb: TextInputCallback => (cb.getPrompt, cb.getDefaultText, "text")
        case cb: NameCallback => (cb.getPrompt, cb.getDefaultName, "text")
        case cb: PasswordCallback => (cb.getPrompt, "Password", "password")
      }
      // prevent transferring password back to client
      if (cb.isInstanceOf[PasswordCallback]) vbox = Empty
      if (hide) vbox.map(hidden(field, _)) else {
        form ++= <label>{ prompt }</label><input id={ field } name={ field } type={ inputType } value={ vbox openOr "" } placeholder={ placeholder }/>;
        if (cb.isInstanceOf[TextInputCallback] && prompt.toLowerCase.contains("openid")) {
          form ++= <div><a href="javascript:void(0)" onclick={
            (SetValById(field, "https://www.google.com/accounts/o8/id") & Run("$('#login-form').submit()")).toJsCmd
          }><span>Sign in with a Google Account</span></a></div>
        }
      }
    }

    val subject: Subject = S.session.get.httpSession.get.attribute(SUBJECT_KEY) match {
      case s: Subject => s // already logged in
      case _ => {
        var requiresInput = false
        val cfgUrl = Platform.getBundle("net.enilink.core")
          .getResource(JAAS_CONFIG_FILE);
        val sCtx = LoginContextFactory.createContext(
          "OpenID", cfgUrl, new CallbackHandler {
            var stage = 0
            def fieldName(index: Int) = "f" + stage + "-" + index
            def handle(callbacks: Array[Callback]) = {
              requiresInput = callbacks.zipWithIndex.foldLeft(false) {
                case (reqInput, (cb, index)) => reqInput || (
                  cb match {
                    case cb @ (_: TextInputCallback | _: NameCallback | _: PasswordCallback) => value(fieldName(index)).isEmpty
                    case _ => false
                  })
              }
              callbacks.zipWithIndex foreach {
                case (cb, index) =>
                  val name = fieldName(index)
                  cb match {
                    case cb: TextOutputCallback => form ++= <div class={
                      "alert" + (cb.getMessageType match {
                        case TextOutputCallback.INFORMATION => " alert-info"
                        case TextOutputCallback.ERROR => " alert-error"
                        case _ => ""
                      })
                    }>{ cb.getMessage }</div>
                    case cb @ (_: TextInputCallback | _: NameCallback | _: PasswordCallback) => handleUserCallback(cb, name, !requiresInput)
                    // special callbacks introduced by enilink
                    case cb: RedirectCallback =>
                      S.redirectTo(cb.getRedirectTo)
                    case cb: RealmCallback =>
                      cb.setContextUrl(S.hostName)
                      cb.setApplicationUrl(S.uri)
                    case cb: ResponseCallback =>
                      val params = S.request.map(_._params.map(e => (e._1, e._2.toArray))) openOr Map.empty
                      cb.setResponseParameters(params)
                    case _ => // ignore unknown callback
                  }
              }
              stage += 1
              if (requiresInput) throw new IOException("need more user information")
            }
          });
        try {
          sCtx.login
          sCtx.getSubject
        } catch {
          case e: LoginException if requiresInput => null // more user input required
          case e: LoginException => {
            form = <div class="alert alert-error"><div><strong>Login failed</strong></div>{ e.getMessage }</div>
            buttons = <button class="btn btn-primary" type="submit">Retry</button>
            null
          }
        }
      }
    }
    if (subject != null) {
      form ++= <div class="alert alert-success"><strong>You are logged in.</strong></div>
      buttons = Nil
    }
    "#fields *" #> form & "#buttons *" #> buttons
  }
}