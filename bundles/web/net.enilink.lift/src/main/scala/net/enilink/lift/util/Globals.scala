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
import net.enilink.komma.core.URIs
import net.enilink.komma.core.URI
import net.enilink.core.security.ISecureModelSet
import net.liftweb.http.Req
import net.enilink.core.security.SecurityUtil
import net.enilink.lift.sitemap.Application
import net.liftweb.http.ParsePath
import net.liftweb.http.LiftRules
import net.liftweb.http.ParsePath
import net.liftweb.sitemap.Loc
import net.enilink.core.blobs.FileStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import net.liftweb.util.Props
import java.nio.file.Paths
import net.enilink.komma.model.IModelSet
import org.osgi.util.tracker.ServiceTracker
import net.enilink.lift.Activator
import org.osgi.framework.FrameworkUtil
import net.enilink.lift.sitemap.Menus

/**
 * A registry for global variables which are shared throughout the application.
 */
object Globals extends Factory {
  private val modelSetTracker = new ServiceTracker[IModelSet, IModelSet](FrameworkUtil.getBundle(getClass).getBundleContext, classOf[IModelSet], null)
  modelSetTracker.open

  implicit val time = new FactoryMaker(Helpers.now _) {}

  implicit val application = new FactoryMaker(() => Empty: Box[Application]) {}
  application.default.set(() => {
    for (
      loc <- S.location or {
        S.request match {
          // support URLs like /classpath/[app]/bootstrap.css
          case Full(r @ Req(mainPath :: app :: "bootstrap" :: _, _, _)) if (mainPath == LiftRules.resourceServerPath) =>
            LiftRules.siteMap.flatMap(_.findLoc(r.withNewPath(ParsePath(List(app), "", true, true))))
          case _ => Empty
        }
      };
      app <- loc.breadCrumbs.find(_.currentValue match {
        case Full(appValue: Application) => true
        case _ => false
      }).flatMap(_.currentValue.asInstanceOf[Box[Application]])
    ) yield {
      // cache app in request var
      application.request.set(Full(app))
      app
    }
  })

  implicit val applicationPath = new FactoryMaker(() => {
    S.getRequestHeader("X-Forwarded-For") match {
      // this is a virtual host hence application is at "/"
      case Full(_) => "/"
      case _ => application.vend.dmap("/")(_.path.mkString("/", "/", "") match {
        case "/" => "/"
        case other => other + "/"
      })
    }
  }) {}

  val contextModelSetRules = LiftRules.RulesSeq[PartialFunction[Req, Box[IModelSet]]]
  implicit val contextModelSet = new FactoryMaker(() => Empty: Box[IModelSet]) {}
  contextModelSet.default.set(() => {
    S.request.flatMap(req => contextModelSetRules.toList.find(_.isDefinedAt(req)) match {
      case Some(f) => f(req)
      case _ => Empty
    }) or contextModel.vend.map(_.getModelSet) or Box.legacyNullTest(modelSetTracker.getService) or {
      // refresh service tracker
      modelSetTracker.close; modelSetTracker.open
      Box.legacyNullTest(modelSetTracker.getService)
    }
  })

  val contextModelRules = LiftRules.RulesSeq[PartialFunction[Req, Box[URI]]]
  implicit val contextModel = new FactoryMaker(() => Empty: Box[IModel]) {}

  val contextResourceRules = LiftRules.RulesSeq[PartialFunction[Req, Box[IReference]]]

  implicit val contextUser = new FactoryMaker(() => UNKNOWN_USER: IReference) {}

  implicit val UNKNOWN_USER: URI = SecurityUtil.UNKNOWN_USER

  implicit val fileStore = new FactoryMaker(() => {
    val path = Box.legacyNullTest(System.getProperty("net.enilink.filestore.path")) map (Paths.get(_)) openOr Platform.getLocation.toFile.toPath.resolve("files")
    new FileStore(path)
  }) {}
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