package net.enilink.platform.lift.sitemap

import net.liftweb.common.{Box, Full}
import net.liftweb.http.S
import net.liftweb.sitemap.Loc.{AnyLocParam, Link, LinkText, LocParam, QueryParameters}
import net.liftweb.sitemap.{Loc, MenuItem}

/**
 * If this parameter is included, the item will not be visible in the menu if it is inactive,
 * but will still be accessible.
 */
case object HideIfInactive extends Loc.LocInfo[Loc.AnyLocParam] {
  def apply(): Box[() => AnyLocParam] = Full(() => HideIfInactive)
}

/**
 * Helper to produce QueryParameters location param that adds the current
 * query parameters to a location's link.
 */
object KeepQueryParameters {
  def apply(): QueryParameters = QueryParameters(() => {
    S.request.toList.flatMap {
      r => r.params.view.flatMap { case (k, v :: _) => Some(k, v) case _ => None }
    }
  })
}

/**
 * Insert this LocParam into your menu if you want new application
 * menu items to be inserted at the same level and after the item
 */
case object AddAppMenusAfter extends Loc.AnyLocParam

/**
 * Insert this LocParam into your menu if you want the
 * application menu items to be children of that menu
 */
case object AddAppMenusUnder extends Loc.AnyLocParam

/**
 * Insert this LocParam into your menu if you want new 
 * menu items to be inserted at the same level and after the item
 */
final case class AddMenusAfter(app : String) extends Loc.AnyLocParam

/**
 * Insert this LocParam into your menu if you want the
 * menu items to be children of that menu
 */
final case class AddMenusUnder(app : String)  extends Loc.AnyLocParam

/**
 * This location value marks a location as the root of an application sitemap.
 * If this parameter is included, the item will not be visible in the menu, but
 * will still be accessible.
 */
case class Application(name: String, path: List[String])

/**
 * Loc that allows to specify a function for dynamically computing its child menus.
 */
case class DynamicLoc(override val name: String,
  override val link: Link[Unit],
  override val text: LinkText[Unit],
  override val params: List[LocParam[Unit]],
  calcDynItems: () => List[MenuItem])
  extends Loc[Unit] {
  override def defaultValue: Box[Unit] = Full(())
  override def supplementalKidMenuItems: List[MenuItem] = calcDynItems() ::: super.supplementalKidMenuItems
  init()
}