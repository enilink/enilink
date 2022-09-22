package net.enilink.platform.lift.util

import java.nio.file.Paths

import org.eclipse.core.runtime.Platform
import org.osgi.framework.FrameworkUtil
import org.osgi.util.tracker.ServiceTracker

import net.enilink.platform.core.Config
import net.enilink.platform.core.PluginConfigModel
import net.enilink.platform.core.blobs.FileStore
import net.enilink.platform.core.security.ISecureModelSet
import net.enilink.platform.core.security.SecurityUtil
import net.enilink.komma.core.IReference
import net.enilink.komma.core.URI
import net.enilink.komma.model.IModel
import net.enilink.komma.model.IModelSet
import net.enilink.platform.lift.sitemap.Application
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Full
import net.liftweb.http.Factory
import net.liftweb.http.LiftRules
import net.liftweb.http.ParsePath
import net.liftweb.http.Req
import net.liftweb.http.S
import net.liftweb.util.Helpers

/**
 * A registry for global variables which are shared throughout the application.
 */
object Globals extends Factory {
  private val osgiBundle = Box.legacyNullTest(FrameworkUtil.getBundle(getClass))

  private val configTracker = osgiBundle.map(bundle => new ServiceTracker[Config, Config](bundle.getBundleContext, classOf[Config], null))
  configTracker.foreach(_.open)

  private val modelSetTracker = osgiBundle.map(bundle => new ServiceTracker[IModelSet, IModelSet](bundle.getBundleContext, classOf[IModelSet], null))
  modelSetTracker.foreach(_.open)

  implicit val config = new FactoryMaker(() => Empty: Box[Config]) {}
  configTracker.foreach { tracker => config.default.set(() => Box.legacyNullTest(tracker.getService)) }

  /**
   * Run a function with the plugin configuration of this function's OSGi bundle.
   */
  def withPluginConfig[T](f: (PluginConfigModel) => T): Unit = {
    val ctx = FrameworkUtil.getBundle(f.getClass).getBundleContext
    val serviceRef = ctx.getServiceReference(classOf[PluginConfigModel])
    val cfgService = ctx.getService(serviceRef)
    try {
      cfgService.begin
      f(cfgService)
    } finally {
      cfgService.end
      ctx.ungetService(serviceRef)
    }
  }

  implicit val time = new FactoryMaker(() => Helpers.now) {}

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
    S.getRequestHeader("VND.eniLINK.dropAppPath") match {
      // this is flagged to drop the application path, which is then at "/"
      // example: application behind proxied virtual host for that app only
      // for choice of header, see RFC 6648 and RFC 4288
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
    }) or contextModel.vend.map(_.getModelSet) or modelSetTracker.map(_.getService()).filter(_ != null) or {
      // refresh service tracker
      modelSetTracker.foreach { tracker =>
        tracker.close()
        tracker.open()
      }
      modelSetTracker.map(_.getService()).filter(_ != null)
    }
  })

  val contextModelRules = LiftRules.RulesSeq[PartialFunction[Req, Box[URI]]]
  implicit val contextModel = new FactoryMaker(() => Empty: Box[IModel]) {}

  val contextResourceRules = LiftRules.RulesSeq[PartialFunction[Req, Box[IReference]]]

  implicit val contextUser = new FactoryMaker(() => UNKNOWN_USER: IReference) {}

  implicit val logoutFuncs = new FactoryMaker(() => Nil: List[() => Unit]) {}

  implicit val UNKNOWN_USER: URI = SecurityUtil.UNKNOWN_USER

  implicit val fileStore = new FactoryMaker(() => {
    val path = Box.legacyNullTest(System.getProperty("net.enilink.filestore.path")) map (Paths.get(_)) openOr Platform.getLocation.toFile.toPath.resolve("files")
    new FileStore(path)
  }) {}

  private[lift] def close : Unit = {
    contextModelSet.default.set(Empty)

    // close trackers
    modelSetTracker.foreach(_.close)
    configTracker.foreach(_.close)
  }
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