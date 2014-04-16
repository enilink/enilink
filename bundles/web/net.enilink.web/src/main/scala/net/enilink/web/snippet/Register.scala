package net.enilink.web.snippet

import scala.xml.Node
import net.enilink.komma.em.concepts.IResource
import net.enilink.komma.core.URIs
import javax.security.auth.Subject
import net.enilink.auth.AccountHelper
import net.enilink.auth.UserPrincipal
import net.enilink.core.ModelSetManager
import net.enilink.vocab.foaf.FOAF
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Full
import net.liftweb.http.S
import net.liftweb.http.Templates
import net.liftweb.util.Helpers._
import net.enilink.lift.util.Globals
import scala.util.control.Exception._
import scala.xml.NodeSeq
import net.liftweb.util.ClearNodes
import net.enilink.vocab.rdf.RDF
import net.enilink.lift.util.Fields
import net.enilink.lift.util.EnilinkRules
import net.liftweb.http.SHtml
import net.liftweb.http.js.JsCmds
import net.liftweb.http.SessionVar
import net.enilink.komma.core.IReference

class Register extends Login {
  def createUser(name: String, email: String, s: Subject) {
    val externalIds = AccountHelper.getExternalIds(s)
    val em = getEntityManager
    if (AccountHelper.findUser(em, externalIds) != null) {
      S.error("The identity is already associated with another account.")
    } else {
      try {
        val user = AccountHelper.createUser(em, name, email)
        // link external IDs
        AccountHelper.linkExternalIds(em, user, externalIds)
        s.getPrincipals.add(new UserPrincipal(user.getURI))
        S.notice("Your user account was successfully created.")
      } catch {
        case e: IllegalArgumentException => S.error("A user with the selected name or email address already exists.")
      }
    }
  }

  def validateEmail(email: String) = {
    var result: Box[String] = Empty
    if (EnilinkRules.emailRegexPattern.vend.matcher(email).matches) {
      if (AccountHelper.hasUserWithEmail(getEntityManager, email)) {
        Fields.error("input-email", "A user with this email address already exists.")
      } else {
        Fields.success("input-email")
        result = Full(email)
      }
    } else {
      Fields.error("input-email", "Please enter a valid email address.")
    }
    result
  }

  def validateUsername(name: String) = {
    var result: Box[String] = Empty
    if (name.length < 2) {
      Fields.error("input-username", "The user name must have at least 2 characters.")
    } else if (!EnilinkRules.usernameRegexPattern.vend.matcher(name).matches) {
      Fields.error("input-username", "Please enter a valid user name.")
    } else if (AccountHelper.hasUserWithName(getEntityManager, name)) {
      Fields.error("input-username", "A user with this name already exists.")
    } else {
      Fields.success("input-username")
      result = Full(name)
    }
    result
  }

  def validatePasswords(pwd: String, confirmedPwd: String) = {
    var result: Box[String] = Empty
    if (pwd == null || pwd.length < 4) {
      Fields.error("input-password", "Please choose a password with at least 4 characters.")
    } else if (pwd != confirmedPwd) {
      Fields.error("input-password", "The passwords do not match.")
    } else {
      Fields.success("input-password")
      result = Full(pwd)
    }
    result
  }

  override def initializeForm = {
    var form: Seq[Node] = <div id="input-username" class="form-group"><label>Your new user name</label><input name="username" type="text" value={ S.param("username") openOr "" } placeholder="&lt;Username&gt;" class="form-control" onblur={
      SHtml.onEvent(name => { validateUsername(name); JsCmds._Noop })._2.toJsCmd
    }/>{ Fields.msgBox("input-username") }</div> ++
      <div id="input-email" class="form-group"><label>Your email address</label><input name="email" type="text" value={ S.param("email") openOr "" } placeholder="&lt;EMail&gt;" class="form-control" onblur={
        SHtml.onEvent(email => { validateEmail(email); JsCmds._Noop })._2.toJsCmd
      }/>{ Fields.msgBox("input-email") }</div>
    if (loginWithEnilink) {
      form ++= <div id="input-password" class="form-group">
                 <label>Password</label><input name="password" type="password" value="" class="form-control"/>
                 <label>Confirm the password</label><input name="password-confirmed" type="password" value="" class="form-control"/>
                 { Fields.msgBox("input-password") }
               </div>
    } else {
      form ++= <hr/>
    }
    form
  }

  override def saveSubjectToSession(s: Subject) {
    if (isLinkIdentity) {
      val currentUser = Globals.contextUser.vend
      if (currentUser != Globals.UNKNOWN_USER) {
        val userPrincipals = s.getPrincipals(classOf[UserPrincipal])
        if (userPrincipals.isEmpty) {
          // simply link new external ids to existing id
          AccountHelper.linkExternalIds(getEntityManager, currentUser, AccountHelper.getExternalIds(s))
          S.notice("The idendity was successfully associated with your account.")
          S.redirectTo("/static/profile")
        } else if (userPrincipals.iterator().next().getId() != currentUser) {
          S.error("The identity is already associated with another account.")
        }
      }
    } else {
      for {
        username <- param("username") flatMap (validateUsername _)
        email <- param("email") flatMap (validateEmail _)
      } createUser(username, email, s)
      super.saveSubjectToSession(s)
    }
  }

  override def render = {
    lazy val username = S.param("username") flatMap (validateUsername _)
    lazy val email = S.param("email") flatMap (validateEmail _)

    var accountCreated = false
    // create an enilink account with username and password
    if (loginWithEnilink) {
      for {
        username <- username
        email <- email
        pwd <- S.param("password")
        confirmedPwd <- S.param("password-confirmed")
        ms <- Globals.contextModelSet.vend
      } {
        if (validatePasswords(pwd, confirmedPwd).isDefined) {
          val encodedPwd = AccountHelper.encodePassword(pwd)
          try {
            AccountHelper.createUser(ms.getMetaDataManager, username, email, encodedPwd)
            S.notice("Your user account was successfully created.")
            accountCreated = true
          } catch {
            case e: IllegalArgumentException => S.error("A user with the selected name or email address already exists.")
          }
        }
      }
    }

    // store user id in session for linking of other external ids
    val currentUser = Globals.contextUser.vend
    getSubjectFromSession match {
      case _ if currentUser != Globals.UNKNOWN_USER => {
        S.withAttrs("mode" -> "link") {
          // access properties in "link" mode
          loginDataVar.props.clear
          super.doRender(false)
        }
      }
      case _ => super.doRender(accountCreated)
    }
  }
}