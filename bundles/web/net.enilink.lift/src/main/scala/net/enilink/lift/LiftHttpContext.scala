package net.enilink.lift

import java.io.File
import java.net.URL

import scala.collection.mutable
import scala.collection.JavaConversions.asScalaSet

import org.osgi.service.http.HttpContext
import org.osgi.util.tracker.BundleTracker
import org.webjars.WebJarAssetLocator

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import net.enilink.lift.sitemap.Application
import net.enilink.lift.util.Globals
import net.liftweb.common.Box.box2Iterable
import net.liftweb.common.Box.option2Box
import net.liftweb.common.Full
import net.liftweb.common.Logger
import net.liftweb.http.LiftRules
import net.liftweb.http.LiftRulesMocker.toLiftRules
import net.liftweb.http.ResourceServer
import net.liftweb.util.Helpers._
import org.osgi.framework.Bundle
import org.osgi.service.http.context.ServletContextHelper
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.ServiceScope._
import org.osgi.service.component.annotations.Reference
import org.osgi.service.http.HttpService

/**
 * ServletContextHelper for HTTP whiteboard that delegates resource lookups to registered
 * Lift-powered bundles
 */
@Component(
  service = Array(classOf[ServletContextHelper]),
  scope = SINGLETON,
  property = Array("osgi.http.whiteboard.context.name=liftweb", "osgi.http.whiteboard.context.path=/"))
class LiftHttpContext extends ServletContextHelper with Logger {
  var liftLifecycleManager: LiftLifecycleManager = null

  @Reference
  def setLiftLifecycleManager(manager: LiftLifecycleManager) {
    this.liftLifecycleManager = manager
    this.webJarLocator = new WebJarAssetLocator(findWebjarAssets)
  }

  // support for WebJars
  def findWebjarAssets = {
    val webjarAssets = new java.util.HashSet[String]
    val seenPaths = mutable.Map.empty[String, Set[Bundle]]

    liftLifecycleManager.bundles.entrySet.toSeq foreach { entry =>
      val bundle = entry.getKey
      val hasWebJars = bundle.getEntry(WebJarAssetLocator.WEBJARS_PATH_PREFIX) != null
      if (hasWebJars) {
        val assets = bundle.findEntries(WebJarAssetLocator.WEBJARS_PATH_PREFIX, "*", true)
        if (assets != null) {
          while (assets.hasMoreElements) {
            val assetUrl = assets.nextElement
            // ensure that the same asset (e.g. same library with same version) is only served by one bundle
            val providers = seenPaths.get(assetUrl.getPath)
            if (providers.isEmpty) {
              webjarAssets.add(assetUrl.toString)
            }
            seenPaths.put(assetUrl.getPath, (providers getOrElse Set.empty) + bundle)
          }
        }
      }
    }

    seenPaths.filter(_._2.size > 1).foreach {
      case (path, bundles) =>
        info("WebJar asset '" + path + "' is served by more than one bundle: " + bundles.map(_.getSymbolicName).mkString(", "))
    }

    webjarAssets
  }

  var webJarLocator: WebJarAssetLocator = null
  val WEBJARS = "/" + ResourceServer.baseResourceLocation + "/webjars"

  // external resource locations
  val resourcePaths = System.getProperty("net.enilink.lift.resourcePaths") match {
    case null => Nil
    case paths => paths.split("\\s+\\s").map(new File(_)).toList
  }

  override def getMimeType(s: String) = {
    // whiteboard implementation determines the mime type itself
    null
  }

  override def getResource(s: String) = {
    debug("""Asked for resource "%s".""" format s)

    val path = if (s.startsWith("/")) s else "/" + s
    if (path.startsWith(WEBJARS)) {
      tryo {
        // try to serve webjars resource
        val assetPath = s.substring(WEBJARS.length + 1)
        val url = webJarLocator.getFullPath(assetPath)
        // assetPath is a "bundleentry://" or "bundleresource://" URL
        new URL(url)
      } openOr null
    } else {
      // serve other resource
      defaultResource(s)
    }
  }

  def defaultResource(s: String): URL = {
    def str[A](p: List[A]) = p.mkString("/", "/", "")
    def list(s: String) = s.stripPrefix("/").split("/").toList
    lazy val baseResourceLocation = list(ResourceServer.baseResourceLocation)
    val resourcePath = list(s)
    val places = if (resourcePath.startsWith("star" :: Nil)) {
      // star stands for any application
      (Globals.application.vend match {
        // /star/some/resource => [application path]/some/resource
        case Full(app) if app.path.headOption.exists(_.nonEmpty) => List(str(app.path ++ resourcePath.tail))
        case _ => Nil
        // /star/some/resource => /some/resource
      }) ++ List(str(resourcePath.tail))
    } else if (resourcePath.startsWith(baseResourceLocation)) {
      val suffix = resourcePath.drop(1)
      // lookup possible appPath in sitemap
      val appPath = LiftRules.siteMap.flatMap(_.findLoc(suffix.head).collectFirst(_.currentValue match {
        case Full(app: Application) => app.path
      })) openOr Nil
      if (appPath.nonEmpty && suffix.startsWith(appPath))
        // /toserve/[application path]/some/resource => /toserve/some/resource
        List(s, str(baseResourceLocation ++ suffix.drop(appPath.length)))
      else List(s)
    } else Globals.application.vend match {
      case Full(app) if app.path.headOption.exists(_.nonEmpty) =>
        // search alternative places
        val appPath = app.path
        if (resourcePath.startsWith(appPath))
          // /[application path]/some/resource => /some/resource
          List(s, str(resourcePath.drop(appPath.length)))
        else resourcePath match {
          case (prefix @ ("templates-hidden" | "resources-hidden")) :: (suffix @ _) =>
            if (suffix.startsWith(appPath)) {
              List(s, str(List(prefix) ++ suffix.drop(appPath.length)))
            } else {
              List(str(List(prefix) ++ appPath ++ suffix), s)
            }
          case _ =>
            // /some/resource => /[application path]/some/resource
            List(str(appPath ++ resourcePath), s)
        }
      // no application context
      case _ => List(s)
    }
    debug("""Places for resource "%s".""" format places)

    val liftBundles = liftLifecycleManager.bundles.entrySet.toSeq.view
    (places.view.flatMap { place =>
      liftBundles.flatMap { b =>
        b.getKey getResource (b.getValue mapResource place) match {
          case null => None
          case res =>
            debug("""Lift-powered bundle "%s" answered for resource "%s".""".format(b.getKey.getSymbolicName, place))
            Some(res)
        }
      }.headOption
    }.headOption) orElse (
      // try to find resource at external location
      places.view.flatMap { place =>
        resourcePaths.view.flatMap { path =>
          val file = new File(path, place.stripPrefix("/"))
          if (file.exists) Some(file.toURI.toURL) else None
        }
      }.headOption) match {
        case None => null
        case Some(res) => res
      }
  }
}