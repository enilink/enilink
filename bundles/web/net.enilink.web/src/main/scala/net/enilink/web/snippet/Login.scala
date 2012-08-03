package net.enilink.web.snippet

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
import net.liftweb.util.Helpers.strToCssBindPromoter
import net.liftweb.util.Helpers
import net.enilink.rap.security.callbacks.ResponseCallback
import java.io.IOException
import java.util.Properties
import java.io.StringReader
import java.io.StringWriter
import net.liftweb.http.provider.HTTPCookie
import net.enilink.rap.security.callbacks.RegisterCallback
import org.eclipse.equinox.security.auth.ILoginContextListener
import java.security.PrivilegedAction

trait SubjectHelper {
  val SUBJECT_KEY = "javax.security.auth.subject";

  def getSubjectFromSession: Box[Subject] = S.session.get.httpSession.get.attribute(SUBJECT_KEY) match {
    case s: Subject => Full(s)
    case _ => Empty
  }
}

class Login extends SubjectHelper {
  val JAAS_CONFIG_FILE = "/resources/jaas.conf";
  val REQUIRE_LOGIN = false || "true"
    .equalsIgnoreCase(System.getProperty("enilink.loginrequired"))

  val loginMethods = List(("IWU Share", "CMIS"), ("OpenID", "OpenID"))

  /**
   * Retrieve a HTTP param while omitting empty strings.
   */
  def value(name: String, loginData: Properties = null) = (S.param(name) match {
    case Full(value) if value.length > 0 => Full(value)
    case _ => Empty
  }) or (if (loginData != null) Box.legacyNullTest(loginData.getProperty(name)) else Empty)

  /**
   * Creates a menu for choosing the login method (OpenID, Kerberos, etc.).
   */
  def createMethodButtons(currentMethod: (String, String)) = {
    <div class="clearfix" style="margin-bottom: 20px">
      <input type="hidden" id="method" name="method" value={ currentMethod._2 }/>
      <div class="btn-group pull-right">
        <a class="btn dropdown-toggle" data-toggle="dropdown" href="#">
          { currentMethod._1 }
          <span class="caret"></span>
        </a>
        <ul class="dropdown-menu">
          {
            loginMethods.filter(_ != currentMethod).flatMap {
              case (label, name) =>
                <li><a href="javascript:void(0)" onclick={
                  (SetValById("method", name) & Run("$('#login-form').submit()")).toJsCmd
                }>{ label }</a></li>
            }
          }
        </ul>
      </div>
    </div>
  }

  def loadLoginData = {
    val loginData = new Properties
    // initialize login data from cookie
    try {
      S.cookieValue("loginData").map(v => loginData.load(new StringReader(v)))
    } catch {
      case e: IOException => // ignore
    }
    loginData
  }

  def saveLoginData(loginData: Properties) {
    val writer = new StringWriter; loginData.store(writer, "")
    S.addCookie(HTTPCookie("loginData", writer.toString).setMaxAge(3600 * 24 * 90 /* 3 months */ ))
  }

  def render = {
    val loginData = loadLoginData
    var currentMethod = S.param("method").orElse(Box.legacyNullTest(loginData.getProperty("method"))).flatMap {
      mParam => loginMethods.collectFirst { case m @ (_, name) if name == mParam => m }
    } getOrElse loginMethods(0)
    loginData.setProperty("method", currentMethod._2)

    var form: Seq[Node] = Nil
    var buttons: Seq[Node] = <button class="btn btn-primary" type="submit">Sign in</button>

    def hidden(name: String, value: String) { form ++= <input type="hidden" name={ name } value={ value }/> }
    def handleUserCallback(cb: Callback, field: String, hide: Boolean) = {
      var vbox = value(field, loginData)
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
    val isRegister = S.attr("mode").exists(_ == "register")

    val session = S.session.get.httpSession.get
    val subject = getSubjectFromSession match {
      case Full(s) => s // already logged in
      case _ => {
        var redirectTo: String = null
        var requiresInput = false
        val cfgUrl = Platform.getBundle("net.enilink.core")
          .getResource(JAAS_CONFIG_FILE);
        val sCtx = LoginContextFactory.createContext(
          currentMethod._2, cfgUrl, new CallbackHandler {
            var stage = 0
            def fieldName(index: Int) = "f-" + currentMethod._2 + "-" + stage + "-" + index
            def handle(callbacks: Array[Callback]) = {
              requiresInput = callbacks.zipWithIndex.foldLeft(false) {
                case (reqInput, (cb, index)) => reqInput || (
                  cb match {
                    case (_: TextInputCallback | _: NameCallback) => {
                      val field = fieldName(index)
                      value(fieldName(index)).map(loginData.setProperty(field, _)).isEmpty
                    }
                    case (_: PasswordCallback) => value(fieldName(index)).isEmpty
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
                    case cb: RegisterCallback =>
                      cb.setRegister(isRegister)
                    case cb: RedirectCallback =>
                      requiresInput = true
                      redirectTo = cb.getRedirectTo
                    case cb: RealmCallback =>
                      cb.setContextUrl(S.hostAndPath)
                      var params = List(("method", currentMethod._2)) ++ S.param("mode").map(("mode", _))
                      cb.setApplicationUrl(Helpers.appendParams(S.hostAndPath + S.uri, params))
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
          session.setAttribute(SUBJECT_KEY, sCtx.getSubject)
          sCtx.getSubject
        } catch {
          case e: LoginException if requiresInput => if (redirectTo != null) { saveLoginData(loginData); S.redirectTo(redirectTo) }; null // user interaction required
          case e: LoginException => {
            form = <div class="alert alert-error"><div><strong>Login failed</strong></div>{ e.getMessage }</div>
            buttons = <button class="btn btn-primary" type="submit">Retry</button>
            null
          }
        }
      }
    }
    if (subject != null) {
      if (isRegister) S.redirectTo(S.hostAndPath + S.uri)
      
      form ++= <div class="alert alert-success"><strong>You are logged in.</strong></div>
      buttons = SHtml.button("Logout", () => session.removeAttribute(SUBJECT_KEY), ("class", "btn btn-primary"))
    } else {
      form = createMethodButtons(currentMethod) ++ form
    }
    saveLoginData(loginData)
    var selectors = "form [action]" #> (S.contextPath + S.uri) &
      "#fields *" #> form &
      "#buttons *" #> buttons
    if (isRegister) selectors &= "#login-form-label *" #> <xml:group><h2>Sign up with</h2>Use an external identity provider.</xml:group>
    selectors
  }
}