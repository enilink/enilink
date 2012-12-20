package net.enilink.lift.util

import net.enilink.komma.model.IModel
import net.enilink.lift.selection.SelectionProvider
import net.liftweb.http.Factory
import net.liftweb.util.Vendor.funcToVender
import net.liftweb.util.Helpers
import org.eclipse.core.runtime.Platform
import scala.Array.canBuildFrom
import net.liftweb.common._
import net.enilink.komma.core.IReference
import net.liftweb.http.S
import javax.security.auth.Subject
import net.enilink.komma.core.URIImpl
import net.enilink.komma.core.URI
import net.enilink.core.security.ISecureModelSet
import net.liftweb.http.Req
import net.enilink.core.security.SecurityUtil
import net.enilink.lift.sitemap.Application

/**
 * A registry for global variables which are shared throughout the application.
 */
object Globals extends Factory {
  implicit val time = new FactoryMaker(Helpers.now _) {}
  implicit val application = new FactoryMaker(() => {
    for (
      loc <- S.location;
      app <- loc.breadCrumbs.find(_.params.contains(Application))
    ) yield app
  }) {}
  implicit val applicationPath = new FactoryMaker(() => { application.vend.dmap("/")(_.link.uriList.mkString("/", "/", "")) }) {}
  implicit val contextModel = new FactoryMaker(() => { Empty: Box[IModel] }) {}
  implicit val contextResource = new FactoryMaker(() => {
    Platform.getExtensionRegistry.getExtensionPoint("net.enilink.lift.selectionProviders").getConfigurationElements.flatMap {
      element =>
        if ("selectionProvider" == element.getName) {
          val selectionProvider = element.createExecutableExtension("class").asInstanceOf[SelectionProvider]
          List(selectionProvider.getSelection)
        } else {
          Nil
        }
    } match {
      case Array(first, _*) => Full(first)
      case _ => Empty
    }
  }: Box[AnyRef]) {}
  implicit val contextUser = new FactoryMaker(() => UNKNOWN_USER: IReference) {}
  implicit val UNKNOWN_USER: URI = SecurityUtil.UNKNOWN_USER
}

// extractor to test if access to context model is allowed
object NotAllowedModel {
  def unapply(m: IModel): Option[IModel] = m.getModelSet match {
    case sms: ISecureModelSet if (!sms.isReadableBy(m.getURI, Globals.contextUser.vend)) => Full(m)
    case _ => Empty // this is not a secure model set
  }

  def unapply(r: Req): Option[IModel] = Globals.contextModel.vend match {
    case Full(m) => unapply(m)
    case _ => Empty // no context model
  }
}