package net.enilink.lift.sitemap

import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.http.S
import net.liftweb.sitemap.Loc
import net.liftweb.sitemap.Loc.Link
import net.liftweb.sitemap.Loc.LinkText
import net.liftweb.sitemap.Loc.LocParam
import net.liftweb.sitemap.Loc.QueryParameters
import net.liftweb.sitemap.MenuItem
import net.liftweb.common.Empty

/**
 * If this parameter is included, the item will not be visible in the menu if it is inactive,
 * but will still be accessable.
 */
case object HideIfInactive extends Loc.AnyLocParam

/**
 * Helper to produce QueryParameters location param that adds the current
 * query parameters to a location's link.
 */
object KeepQueryParameters {
  def apply() = QueryParameters(() => {
    S.request.toList.flatMap {
      r => r.params.view.flatMap { case (k, v :: _) => Full(k, v) case _ => Empty }
    }
  })
}

/**
 * Insert this LocParam into your menu if you want new application
 * menu items to be inserted at the same level and after the item
 */
final case object AddAppMenusAfter extends Loc.AnyLocParam

/**
 * Insert this LocParam into your menu if you want the
 * application menu items to be children of that menu
 */
final case object AddAppMenusUnder extends Loc.AnyLocParam

/**
 * Insert this LocParam into your menu if you want new 
 * menu items to be inserted at the same level and after the item
 */
final case class AddMenusAfter(val app : String) extends Loc.AnyLocParam

/**
 * Insert this LocParam into your menu if you want the
 * menu items to be children of that menu
 */
final case class AddMenusUnder(val app : String)  extends Loc.AnyLocParam

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
  val calcDynItems: () => List[MenuItem])
  extends Loc[Unit] {
  override def defaultValue: Box[Unit] = Full(())
  override def supplementalKidMenuItems: List[MenuItem] = calcDynItems() ::: super.supplementalKidMenuItems
  init()
}