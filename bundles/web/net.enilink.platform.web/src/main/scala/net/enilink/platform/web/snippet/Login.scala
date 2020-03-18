package net.enilink.platform.web.snippet

import java.io.IOException
import java.io.StringWriter

import scala.Array.canBuildFrom
import scala.collection._
import scala.collection.JavaConversions.mapAsJavaMap
import scala.xml.Node
import scala.xml.NodeBuffer
import scala.xml.NodeSeq
import scala.xml.NodeSeq.seqToNodeSeq
import scala.xml.Text

import org.eclipse.equinox.security.auth.ILoginContext
import org.eclipse.equinox.security.auth.LoginContextFactory

import javax.security.auth.Subject
import javax.security.auth.callback.Callback
import javax.security.auth.callback.CallbackHandler
import javax.security.auth.callback.NameCallback
import javax.security.auth.callback.PasswordCallback
import javax.security.auth.callback.TextInputCallback
import javax.security.auth.callback.TextOutputCallback
import javax.security.auth.login.LoginException
import net.enilink.platform.core.security.SecurityUtil
import net.enilink.platform.lift.util.EnilinkRules
import net.enilink.platform.lift.util.Globals
import net.enilink.platform.security.callbacks.RealmCallback
import net.enilink.platform.security.callbacks.RedirectCallback
import net.enilink.platform.security.callbacks.RegisterCallback
import net.enilink.platform.security.callbacks.ResponseCallback
import net.liftweb.common.Box
import net.liftweb.common.Box.box2Option
import net.liftweb.common.Empty
import net.liftweb.common.Full
import net.liftweb.http.S
import net.liftweb.http.SHtml
import net.liftweb.http.SessionVar
import net.liftweb.http.Templates
import net.liftweb.http.TransientRequestVar
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JsCmds
import net.liftweb.http.provider.HTTPCookie
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers.strToCssBindPromoter

class Login {
  class DelegatingCallbackHandler extends CallbackHandler {
    var delegate: CallbackHandler = _
    def handle(callbacks: Array[Callback]) = delegate.handle(callbacks)
  }

  /**
   * Captures a login context instance for storing it in a user session.
   */
  class State {
    var referer: Box[String] = Empty
    var method: String = _
    var context: ILoginContext = _
    var handler: DelegatingCallbackHandler = new DelegatingCallbackHandler
    var params: Map[String, List[String]] = Map.empty
  }

  object loginState extends SessionVar[Box[State]](Empty)

  object LoginDataHelpers {
    import EnilinkRules.LOGIN_METHODS
    import net.liftweb.json._

    def loadLoginData = {
      var loginData = new mutable.HashMap[String, Any]
      // initialize login data from cookie
      S.cookieValue("loginData").map {
        v =>
          parseOpt(Helpers.urlDecode(v)) match {
            case Some(json: JObject) => loginData ++= json.values
            case _ => // invalid data
          }
      }
      loginData
    }

    def loginMethods = if (isLinkIdentity) LOGIN_METHODS.tail else LOGIN_METHODS
  }

  case class LoginData(props: mutable.Map[String, Any], currentMethod: (String, String)) {
    implicit val formats = net.liftweb.json.DefaultFormats
    import net.liftweb.json.JsonAST
    import net.liftweb.json.Extraction._
    import net.liftweb.json.Printer._
    def save {
      S.addCookie(HTTPCookie("loginData", Helpers.urlEncode(compact(JsonAST.render(decompose(props.toMap))))).setMaxAge(3600 * 24 * 90 /* 3 months */ ))
    }
  }

  object loginDataVar extends TransientRequestVar[LoginData]({
    val props = LoginDataHelpers.loadLoginData
    val methods = LoginDataHelpers.loginMethods
    var currentMethod = param("method").orElse(props.get("method")).flatMap {
      mParam => methods.collectFirst { case m @ (_, name) if name == mParam => m }
    } getOrElse methods(0)
    if (!props.get("method").exists(_ == currentMethod._2)) {
      // clear data if login method has been changed
      props.clear
    }
    props("method") = currentMethod._2
    LoginData(props, currentMethod)
  })

  val SUBJECT_KEY = "javax.security.auth.subject";
  def getSubjectFromSession: Box[Subject] = S.containerSession.flatMap(_.attribute(SUBJECT_KEY) match {
    case s: Subject => Full(s)
    case _ => Empty
  })
  def saveSubjectToSession(s: Subject) = S.containerSession.foreach(_.setAttribute(SUBJECT_KEY, s))

  def isLinkIdentity = S.attr("mode").exists(_ == "link") && Globals.contextUser.vend != SecurityUtil.UNKNOWN_USER

  def getEntityManager = Globals.contextModelSet.vend.map(_.getMetaDataManager) openOrThrowException ("Unable to retrieve the model set")

  /**
   * Retrieve a HTTP param from the login state or from the request
   */
  def param(name: String) = loginState.get.flatMap(_.params.get(name).flatMap(_.headOption)) or S.param(name)

  /**
   * Retrieve a HTTP param while omitting empty strings.
   */
  def value(cb: Callback, name: String, values: Map[String, Any] = Map.empty) = (cb match {
    // automatically login user after successful registration
    case _ if !loginWithEnilink => Empty
    case _: NameCallback => param("username").filter(_.length > 0)
    case _: PasswordCallback => param("password").filter(_.length > 0)
    case _ => Empty
  }) or (param(name).filter(_.length > 0)) or values.get(name).map(_.toString)

  /**
   * Creates a menu for choosing the login method (OpenID, Kerberos, etc.).
   */
  def createMethodButtons(currentMethod: (String, String)) = {
    <div class="clearfix" style="margin-bottom: 20px">
      <input type="hidden" id="method" name="method" value={ currentMethod._2 }/>
      <ul class="nav nav-pills pull-right">
        {
          LoginDataHelpers.loginMethods.size match {
            case s if (s > 1) =>
              <li class="dropdown">
                <a class="dropdown-toggle" data-toggle="dropdown" href="#">
                  { currentMethod._1 }
                  <span class="caret"></span>
                </a>
                <ul class="dropdown-menu">
                  {
                    LoginDataHelpers.loginMethods.filter(_ != currentMethod).flatMap {
                      case (label, name) =>
                        <li><a href="javascript:void(0)" onclick={
                          (SetValById("method", name) & Run("$('#login-form').submit()")).toJsCmd
                        }>{ label }</a></li>
                    }
                  }
                </ul>
              </li>
            case _ =>
              <li class="disabled">
                <a href="#">
                  { currentMethod._1 }
                </a>
              </li>
          }
        }
      </ul>
    </div>
  }

  def hidden(name: String, value: String): NodeSeq = <input type="hidden" name={ name } value={ value }/>

  def handleUserCallback(cb: Callback, field: String, hide: Boolean, loginData: LoginData): NodeSeq = {
    var vbox = value(cb, field, loginData.props)
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
    if (hide) vbox.toList.flatMap(hidden(field, _)) else {
      val form = <label>{ prompt }</label><input id={ field } name={ field } type={ inputType } value={ vbox openOr "" } placeholder={ placeholder } class="form-control"/>;
      if (cb.isInstanceOf[TextInputCallback] && prompt.toLowerCase.contains("openid")) {
        form ++= <div><a href="javascript:void(0)" onclick={
          (SetValById(field, "https://www.google.com/accounts/o8/id") & Run("$('#login-form').submit()")).toJsCmd
        }><span>Sign in with a Google Account</span></a></div>
      }
      form
    }
  }

  def loginWithEnilink = loginDataVar.currentMethod._1 == "eniLINK"

  def initializeForm: NodeSeq = Nil

  def render = doRender(false)

  def doRender(accountCreated: Boolean) = {
    val isRegister = this.isInstanceOf[Register] && Globals.contextUser.vend == SecurityUtil.UNKNOWN_USER
    val currentMethod = loginDataVar.currentMethod

    var form: NodeSeq = if (accountCreated || isLinkIdentity) Nil else initializeForm
    var buttons: Seq[Node] = <button class="btn btn-primary" type="submit">Sign { if (isRegister) "up" else "in" }</button>

    val session = S.session.get.httpSession.get
    val subject = getSubjectFromSession match {
      case Full(s) if !(isRegister || isLinkIdentity) => s // already logged in
      // in the process of creating an enilink account with username and password
      case _ if isRegister && loginWithEnilink && !accountCreated => null
      case _ => {
        var redirectTo: String = null
        var requiresInput = false

        // use login context from session or create a new one
        val state = loginState.get match {
          case Full(state) if state.method == currentMethod._2 => state
          case _ => {
            val state = new State
            state.referer = S.referer flatMap { r => if (r.startsWith(S.hostAndPath)) Full(r) else Empty }
            state.method = currentMethod._2
            state.context = LoginContextFactory.createContext(state.method, EnilinkRules.JAAS_CONFIG_URL, state.handler)
            loginState.set(Full(state))
            state
          }
        }
        val loginCtx = state.context
        // update handler, since the handle method is actually a closure over some variables of this snippet instance
        state.handler.delegate = new CallbackHandler {
          var stage = 0
          def fieldName(index: Int) = "f-" + currentMethod._2 + "-" + stage + "-" + index
          def handle(callbacks: Array[Callback]) = {
            requiresInput = callbacks.zipWithIndex.foldLeft(false) {
              case (reqInput, (cb, index)) => reqInput || (
                cb match {
                  case (_: TextInputCallback | _: NameCallback) => {
                    val field = fieldName(index)
                    value(cb, fieldName(index)).map(loginDataVar.props.update(field, _)).isEmpty
                  }
                  case (_: PasswordCallback) => value(cb, fieldName(index)).isEmpty
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
                      case TextOutputCallback.ERROR => " alert-danger"
                      case _ => ""
                    })
                  }>{ cb.getMessage }</div>
                  case cb @ (_: TextInputCallback | _: NameCallback | _: PasswordCallback) => form ++= handleUserCallback(cb, name, !requiresInput, loginDataVar)
                  // special callbacks introduced by enilink
                  case cb: RegisterCallback =>
                    cb.setRegister(!accountCreated && (isRegister || isLinkIdentity))
                  case cb: RedirectCallback =>
                    requiresInput = true
                    redirectTo = cb.getRedirectTo
                    state.params = S.request.map(_._params) openOr Map.empty
                  case cb: RealmCallback =>
                    cb.setContextUrl(S.hostAndPath)
                    cb.setApplicationUrl(S.hostAndPath + S.uri)
                  case cb: ResponseCallback =>
                    val params = S.request.map(_._params.map(e => (e._1, e._2.toArray))) openOr Map.empty
                    cb.setResponseParameters(params)
                    // add parameters for fields as hidden inputs
                    state.params foreach {
                      case (k, v :: Nil) if k.startsWith("f-") => form ++= hidden(k, v)
                      case _ =>
                    }
                  case _ => // ignore unknown callback
                }
            }
            stage += 1
            if (requiresInput) throw new IOException("need more user information")
          }
        }

        try {
          loginCtx.login
          try {
            saveSubjectToSession(loginCtx.getSubject)
            // register a logout function that calls logout() on our LoginContext
            Globals.logoutFuncs.session.set {
              Globals.logoutFuncs.vend :+ (() => loginCtx.logout)
            }
          } finally {
            loginState.remove
          }
          // redirect to origin if login is successful
          if (!isRegister) S.redirectTo(state.referer openOr "/")
          loginCtx.getSubject
        } catch {
          case e: LoginException if requiresInput =>
            if (redirectTo != null) { loginDataVar.save; S.redirectTo(redirectTo) }; null // user interaction required
          case e: LoginException => {
            var cause = e
            // required for Equinox security to retrieve the
            // real cause for this exception
            if (cause.getCause.isInstanceOf[LoginException]) {
              cause = cause.getCause.asInstanceOf[LoginException]
            }

            form = <div class="alert alert-danger"><div><strong>Login failed</strong></div>{ cause.getMessage }</div>
            buttons = <button class="btn btn-primary" type="submit">Retry</button>
            null
          }
        }
      }
    }
    if (subject != null && Globals.contextUser.vend != Globals.UNKNOWN_USER) {
      if (isRegister) S.redirectTo(S.hostAndPath + "/profile")
      form ++= <div class="alert alert-success"><strong>You are logged in.</strong></div>
      buttons = Nil
    } else {
      form = createMethodButtons(currentMethod) ++ form
    }
    loginDataVar.save
    var selectors = "form [action]" #> (S.contextPath + S.uri) &
      "#fields *" #> form &
      "#buttons *" #> buttons
    if (isRegister) {
      selectors &= "#login-form-label *" #> <xml:group><h2>Sign up</h2>Select an identity provider.</xml:group>
    } else if (isLinkIdentity) {
      selectors &= "#login-form-label *" #> <xml:group><h2>Link other identity</h2>Select an identity provider.</xml:group>
    }
    "*" #> Templates("templates-hidden" :: "loginform" :: Nil).map(ns => selectors(ns))
  }
}
