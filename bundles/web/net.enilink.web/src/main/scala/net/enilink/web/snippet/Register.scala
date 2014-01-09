package net.enilink.web.snippet

import scala.xml.Node
import net.enilink.komma.em.concepts.IResource
import net.enilink.komma.core.URIImpl
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
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers.strToCssBindPromoter
import net.enilink.lift.util.Globals
import scala.util.control.Exception._
import scala.xml.NodeSeq
import net.liftweb.util.ClearNodes
import net.enilink.vocab.rdf.RDF

class Register extends SubjectHelper {
  def getEntityManager = ModelSetManager.INSTANCE.getModelSet.getMetaDataManager

  def createUser(s: Subject, name: String): Box[String] = {
    val em = getEntityManager
    try {
      val user = AccountHelper.createUser(em, name)
      // link external IDs
      val externalIds = AccountHelper.getExternalIds(s)
      AccountHelper.linkExternalIds(em, user, externalIds)
      s.getPrincipals.add(new UserPrincipal(user.getURI))
      Empty
    } catch {
      case iae: IllegalArgumentException => Full("A user with this name already exists.")
    }
  }

  def render: NodeSeq => NodeSeq = {
    val currentUser = Globals.contextUser.vend
    // store user id in session for linking of other external ids
    if (currentUser != Globals.UNKNOWN_USER) Globals.contextUser.session.set(() => currentUser)
    getSubjectFromSession match {
      case Full(s) if currentUser == Globals.UNKNOWN_USER => {
        var form: Seq[Node] = Nil
        var buttons: Seq[Node] = <button class="btn btn-primary" type="submit">Sign up</button>

        val username = S.param("f-username")
        if (username.isDefined) username.flatMap(createUser(s, _)) match {
          case Full(msg) => form ++= <div class="alert alert-error">{ msg }</div>
          case _ =>
        }

        form ++= <label>Choose a user name</label><input id="f-username" name="f-username" type="text" value="" placeholder="&lt;username&gt;"/>;

        var selectors =
          "#login-form-div [data-lift!]" #> "login" &
            "form [action]" #> (S.contextPath + S.uri) &
            "#fields *" #> form &
            "#buttons *" #> buttons &
            "#login-form-label *" #> <h2>Finish the registration</h2>

        // reuse the existing loginform template
        "*" #> selectors(Templates("templates-hidden" :: "loginform" :: Nil).openOrThrowException("Missing login form template."))
      }
      case Full(s) if s.getPrincipals(classOf[UserPrincipal]).isEmpty =>
        // simply link new external ids to existing id
        AccountHelper.linkExternalIds(getEntityManager, currentUser, AccountHelper.getExternalIds(s))
        s.getPrincipals.add(new UserPrincipal(currentUser.getURI))
        Globals.contextUser.session.remove
        S.redirectTo("/static/profile")
        ClearNodes
      case _ => "*" #> <div data-lift="embed?what=loginform;mode=register"></div>
    }
  }
}