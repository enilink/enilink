package net.enilink.lift.sitemap

import net.liftweb.sitemap.Loc._
import net.liftweb.sitemap.Loc
/**
 * This parameter marks a location as the root of an application sitemap.
 * If this parameter is included, the item will not be visible in the menu, but
 * will still be accessible.
 */
case object Application extends AnyLocParam 