package net.enilink.platform.lift

import net.liftweb.common.Box
import net.liftweb.sitemap.SiteMap

/**
 * Configuration of a Lift-powered bundle.
 */
case class LiftBundleConfig(module: Box[AnyRef], packages: Seq[String], startLevel: Int) {
  var booted : Boolean = false
  var sitemapMutator: Box[SiteMap => SiteMap] = None
  def mapResource(s: String) = s.replaceAll("//", "/")
}