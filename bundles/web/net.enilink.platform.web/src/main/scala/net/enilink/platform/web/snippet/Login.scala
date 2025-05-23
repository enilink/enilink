package net.enilink.platform.web.snippet

import net.enilink.komma.core.IEntityManager
import net.enilink.platform.core.security.{LoginUtil, SecurityUtil}
import net.enilink.platform.lift.util.Globals
import net.enilink.platform.security.callbacks.{RealmCallback, RedirectCallback, RegisterCallback, ResponseCallback}
import net.liftweb.common.Box.box2Option
import net.liftweb.common.{Box, Empty, Full}
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.provider.HTTPCookie
import net.liftweb.http.{S, SessionVar, Templates, TransientRequestVar}
import net.liftweb.json.DefaultFormats
import net.liftweb.util.Helpers._
import net.liftweb.util.{CssSel, Helpers}
import org.eclipse.equinox.security.auth.{ILoginContext, LoginContextFactory}

import java.io.IOException
import java.util.Collections
import javax.security.auth.Subject
import javax.security.auth.callback._
import javax.security.auth.login.LoginException
import scala.collection._
import scala.jdk.CollectionConverters._
import scala.xml.NodeSeq.seqToNodeSeq
import scala.xml.{Elem, Node, NodeSeq}

class Login {
  class DelegatingCallbackHandler extends CallbackHandler {
    var delegate: CallbackHandler = _
    def handle(callbacks: Array[Callback]): Unit = delegate.handle(callbacks)
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
    import net.liftweb.json._

    lazy val methods: mutable.Buffer[(String, String)] = LoginUtil.getLoginMethods.asScala.map(m => (m.getFirst, m.getSecond))

    def loadLoginData: mutable.HashMap[String, Any] = {
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

    def loginMethods: mutable.Buffer[(String, String)] = if (isLinkIdentity) methods.tail else methods
  }

  case class LoginData(props: mutable.Map[String, Any], currentMethod: (String, String)) {
    implicit val formats: DefaultFormats.type = net.liftweb.json.DefaultFormats
    import net.liftweb.json.Extraction._
    import net.liftweb.json.JsonAST
    def save() : Unit = {
      S.addCookie(HTTPCookie("loginData", Helpers.urlEncode(JsonAST.compactRender(decompose(props.toMap)))).setMaxAge(3600 * 24 * 90 /* 3 months */ ))
    }
  }

  object loginDataVar extends TransientRequestVar[LoginData]({
    val props = LoginDataHelpers.loadLoginData
    val methods = LoginDataHelpers.loginMethods
    val currentMethod = param("method").orElse(props.get("method")).flatMap {
      mParam => methods.collectFirst { case m @ (_, name) if name == mParam => m }
    } getOrElse methods.head
    if (!props.get("method").contains(currentMethod._2)) {
      // clear data if login method has been changed
      props.clear
    }
    props("method") = currentMethod._2
    LoginData(props, currentMethod)
  })

  val SUBJECT_KEY = "javax.security.auth.subject"

  def getSubjectFromSession: Box[Subject] = S.containerSession.flatMap(_.attribute(SUBJECT_KEY) match {
    case s: Subject => Full(s)
    case _ => Empty
  })
  def saveSubjectToSession(s: Subject): Unit = S.containerSession.foreach(_.setAttribute(SUBJECT_KEY, s))

  def isLinkIdentity: Boolean = S.attr("mode").exists(_ == "link") && Globals.contextUser.vend != SecurityUtil.UNKNOWN_USER

  def getEntityManager: IEntityManager = Globals.contextModelSet.vend.map(_.getMetaDataManager) openOrThrowException "Unable to retrieve the model set"

  /**
   * Retrieve a HTTP param from the login state or from the request
   */
  def param(name: String): Box[String] = loginState.get.flatMap(_.params.get(name).flatMap(_.headOption)) or S.param(name)

  /**
   * Retrieve a HTTP param while omitting empty strings.
   */
  def value(cb: Callback, name: String, values: Map[String, Any] = Map.empty): Box[String] = (cb match {
    // automatically login user after successful registration
    case _ if !loginWithEnilink => Empty
    case _: NameCallback => param("username").filter(_.nonEmpty)
    case _: PasswordCallback => param("password").filter(_.nonEmpty)
    case _ => Empty
  }) or param(name).filter(_.nonEmpty) or values.get(name).map(_.toString)

  /**
   * Creates a menu for choosing the login method (OpenID, Kerberos, etc.).
   */
  def createMethodButtons(currentMethod: (String, String)): Elem = {
    <div class="clearfix" style="margin-bottom: 20px">
      <input type="hidden" id="method" name="method" value={ currentMethod._2 }/>
      <ul class="nav nav-pills pull-right">
        {
          LoginDataHelpers.loginMethods.size match {
            case s if s > 1 =>
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
      val form = <label>{ prompt }</label><input id={ field } name={ field } type={ inputType } value={ vbox openOr "" } placeholder={ placeholder } class="form-control"/>
      if (cb.isInstanceOf[TextInputCallback] && prompt.toLowerCase.contains("openid")) {
        form ++= <div><a href="javascript:void(0)" onclick={
          (SetValById(field, "https://www.google.com/accounts/o8/id") & Run("$('#login-form').submit()")).toJsCmd
        }><span>Sign in with a Google Account</span></a></div>
      }
      form
    }
  }

  def loginWithEnilink: Boolean = loginDataVar.currentMethod._1 == "eniLINK"

  def initializeForm: NodeSeq = Nil

  def render: CssSel = doRender(false)

  def doRender(accountCreated: Boolean): CssSel = {
    val isRegister = this.isInstanceOf[Register] && Globals.contextUser.vend == SecurityUtil.UNKNOWN_USER
    val currentMethod = loginDataVar.currentMethod

    var form: NodeSeq = if (accountCreated || isLinkIdentity) Nil else initializeForm
    var buttons: Seq[Node] = <button class="btn btn-primary" type="submit">Sign { if (isRegister) "up" else "in" }</button>

    val subject = getSubjectFromSession match {
      case Full(s) if !(isRegister || isLinkIdentity) => s // already logged in
      // in the process of creating an enilink account with username and password
      case _ if isRegister && loginWithEnilink && !accountCreated => null
      case _ =>
        var redirectTo: String = null
        var requiresInput = false

        // use login context from session or create a new one
        val state = loginState.get match {
          case Full(state) if state.method == currentMethod._2 => state
          case _ =>
            val state = new State
            state.referer = S.referer flatMap { r => if (r.startsWith(S.hostAndPath)) Full(r) else Empty }
            state.method = currentMethod._2
            state.context = LoginContextFactory.createContext(state.method, LoginUtil.getJaasConfigUrl, state.handler)
            loginState.set(Full(state))
            state
        }
        val loginCtx = state.context
        // update handler, since the handle method is actually a closure over some variables of this snippet instance
        state.handler.delegate = new CallbackHandler {
          var stage = 0
          def fieldName(index: Int): String = "f-" + currentMethod._2 + "-" + stage + "-" + index
          def handle(callbacks: Array[Callback]): Unit = {
            requiresInput = callbacks.zipWithIndex.foldLeft(false) {
              case (reqInput, (cb, index)) => reqInput || (
                cb match {
                  case _: TextInputCallback | _: NameCallback =>
                    val field = fieldName(index)
                    value(cb, fieldName(index)).map(loginDataVar.props.update(field, _)).isEmpty
                  case _: PasswordCallback => value(cb, fieldName(index)).isEmpty
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
                    val params = S.request.map(_._params.map(e => (e._1, e._2.toArray)).asJava)
                    cb.setResponseParameters(params openOr Collections.emptyMap())
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
          loginCtx.login()
          try {
            saveSubjectToSession(loginCtx.getSubject)
            // register a logout function that calls logout() on our LoginContext
            Globals.logoutFuncs.session.set {
              Globals.logoutFuncs.vend :+ (() => loginCtx.logout())
            }
          } finally {
            loginState.remove
          }
          // redirect to origin if login is successful
          if (!isRegister) S.redirectTo(state.referer openOr "/")
          loginCtx.getSubject
        } catch {
          case e: LoginException if requiresInput =>
            if (redirectTo != null) { loginDataVar.save(); S.redirectTo(redirectTo) }; null // user interaction required
          case e: LoginException =>
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
    if (subject != null && Globals.contextUser.vend != Globals.UNKNOWN_USER) {
      if (isRegister) S.redirectTo(S.hostAndPath + "/profile")
      form ++= <div class="alert alert-success"><strong>You are logged in.</strong></div>
      buttons = Nil
    } else {
      form = createMethodButtons(currentMethod) ++ form
    }
    loginDataVar.save()
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
