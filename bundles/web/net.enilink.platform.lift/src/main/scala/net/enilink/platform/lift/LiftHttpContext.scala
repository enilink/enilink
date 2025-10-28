package net.enilink.platform.lift

import net.enilink.platform.lift.sitemap.Application
import net.enilink.platform.lift.util.Globals
import net.liftweb.common.{Full, Logger}
import net.liftweb.http.{LiftRules, ResourceServer}
import org.osgi.framework.Bundle
import org.osgi.service.component.annotations.{Component, Reference, ServiceScope}
import org.osgi.service.http.context.ServletContextHelper

import java.io.File
import java.net.URL
import java.util
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.matching.Regex

/**
 * ServletContextHelper for HTTP whiteboard that delegates resource lookups to registered
 * Lift-powered bundles
 */
@Component(
  service = Array(classOf[ServletContextHelper]),
  scope = ServiceScope.BUNDLE,
  property = Array(
    "osgi.http.whiteboard.context.name=liftweb",
    "osgi.http.whiteboard.context.path=/"))
class LiftHttpContext extends ServletContextHelper with Logger {
  @Reference
  var liftLifecycleManager: LiftLifecycleManager = _

  private val WEBJARS_PATH_PREFIX = "META-INF/resources/webjars"

  /**
   * Holds information about available WebJar assets
   */
  private class WebJarAssets {
    val allPaths = new util.TreeMap[String, String]
    val pathsByWebjar = new util.HashMap[String, util.TreeMap[String, String]]

    /**
     * Finds the full path for a given partial path in the provided index
     *
     * @param partialPath the partial path to search for
     * @param index       the index to search in
     * @return an option containing the full path if found, or None if not found or multiple matches exist
     */
    def getFullPath(partialPath: String, index: util.NavigableMap[String, String]): Option[String] = {
      val reversedPartialPath = partialPath.split("/").reverse.mkString("/")
      val fullPathTail = index.tailMap(reversedPartialPath)
      val matches = fullPathTail.entrySet().asScala.takeWhile(_.getKey.startsWith(reversedPartialPath)).map(_.getValue)

      if (matches.isEmpty) {
        None
      } else if (matches.size > 1) {
        _logger.warn("Multiple matches found for " + partialPath + ": {}. Please provide a more specific path, for example by including a version number.", matches.mkString(", "))
        None
      } else {
        matches.headOption
      }
    }
  }

  /**
   * Finds all WebJar assets in the registered Lift-powered bundles
   *
   * @return a WebJarAssets instance containing the found assets
   */
  private def findWebjarAssets: WebJarAssets = {
    val webjarAssets = new WebJarAssets
    val seenPaths = mutable.Map.empty[String, Set[Bundle]]

    liftLifecycleManager.bundles.entrySet.asScala.toSeq foreach { entry =>
      val bundle = entry.getKey
      val hasWebJars = bundle.getEntry(WEBJARS_PATH_PREFIX) != null
      if (hasWebJars) {
        val assets = bundle.findEntries(WEBJARS_PATH_PREFIX, "*", true)
        if (assets != null) {
          while (assets.hasMoreElements) {
            val assetUrl = assets.nextElement
            // path in format META-INF/resources/webjars/{webjar}/{version}/{asset}
            val path = assetUrl.getPath.stripPrefix("/")
            // ensure that the same asset (e.g. same library with same version) is only served by one bundle
            val providers = seenPaths.get(path)
            if (providers.isEmpty) {
              val reversedPath = path.split("/").reverse.mkString("/")
              webjarAssets.allPaths.put(reversedPath, assetUrl.toString)

              val webjar = path.substring(WEBJARS_PATH_PREFIX.length).stripPrefix("/").takeWhile(_ != '/')
              webjarAssets.pathsByWebjar
                .computeIfAbsent(webjar, _ => new util.TreeMap[String, String])
                .put(reversedPath, assetUrl.toString)
            }
            seenPaths.put(path, (providers getOrElse Set.empty) + bundle)
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

  private var webJarAssets: WebJarAssets = _
  private val WEBJARS = "/" + ResourceServer.baseResourceLocation + "/webjars/"
  private val webjarWithWildcardPattern: Regex = "^([^/]+)/\\*/(.*)$".r

  // external resource locations
  private val resourcePaths = System.getProperty("net.enilink.platform.lift.resourcePaths") match {
    case null => Nil
    case paths => paths.split("\\s+\\s").map(new File(_)).toList
  }

  override def getMimeType(s: String): Null = {
    // whiteboard implementation determines the mime type itself
    null
  }

  override def getResource(s: String): URL = {
    debug("""Get resource "%s".""" format s)
    if (webJarAssets == null) {
      // lazy initialize web jars locator
      this.synchronized {
        if (webJarAssets == null) {
          webJarAssets = findWebjarAssets
        }
      }
    }
    val path = if (s.startsWith("/")) s else "/" + s
    if (path.startsWith(WEBJARS)) {
      // serve webjar asset
      val assetPath = s.substring(WEBJARS.length)
      val url: Option[String] = assetPath match {
        // assetPath is in format {webjar}/*/{pathInWebjar}
        case webjarWithWildcardPattern(webjar, pathInWebjar) =>
          Option(webJarAssets.pathsByWebjar.get(webjar)).flatMap { paths =>
            webJarAssets.getFullPath(pathInWebjar, paths)
          }
        case _ =>
          webJarAssets.getFullPath(assetPath, webJarAssets.allPaths)
      }
      // url is a "bundleentry://" or "bundleresource://" URL
      url.map(new URL(_)).orNull
    } else {
      // serve other resource
      defaultResource(s)
    }
  }

  private def defaultResource(s: String): URL = {
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
          case (prefix@("templates-hidden" | "resources-hidden")) :: (suffix@_) =>
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

    val liftBundles = liftLifecycleManager.bundles.entrySet.asScala.toSeq.view
    places.view.flatMap { place =>
      liftBundles.flatMap { b =>
        b.getKey getResource (b.getValue mapResource place) match {
          case null => None
          case res =>
            debug("""Lift-powered bundle "%s" answered for resource "%s".""".format(b.getKey.getSymbolicName, place))
            Some(res)
        }
      }.headOption
    }.headOption orElse (
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