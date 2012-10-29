package net.enilink.web.snippet

import scala.xml.Node

import net.enilink.komma.concepts.IResource
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

class Register extends SubjectHelper {
  def linkUserName(s: Subject, name: String): Box[String] = {
    val userId = URIImpl.createURI("http://enilink.net/users").appendLocalPart(URIImpl.encodeOpaquePart(name, false))

    val em = ModelSetManager.INSTANCE.getModelSet.getMetaDataManager
    val result = synchronized {
      // TODO Is it possible to use database locking here?
      if (em.createQuery("ask { ?user ?p ?o }").setParameter("user", userId).getBooleanResult) {
        Full("A user with this name already exists.")
      } else {
        val externalIds = AccountHelper.getExternalIds(s)
        AccountHelper.linkExternalIds(em, userId, externalIds)
        s.getPrincipals.add(new UserPrincipal(userId))
        Empty
      }
    }
    if (result.isEmpty) {
      // store the user name
      em.find(userId, classOf[IResource]).addProperty(FOAF.PROPERTY_NICK, name)
    }
    result
  }

  def render = {
    getSubjectFromSession match {
      case Full(s) => {
        var form: Seq[Node] = Nil
        var buttons: Seq[Node] = <button class="btn btn-primary" type="submit">Sign up</button>

        val username = S.param("f-username")
        if (username.isDefined) username.flatMap(linkUserName(s, _)) match {
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
        "*" #> selectors(Templates("templates-hidden" :: "loginform" :: Nil).openTheBox)
      }
      case _ => "*" #> <div data-lift="embed?what=loginform;mode=register"></div>
    }
  }
}