package net.enilink.lift.sitemap

import net.liftweb.sitemap._
import net.liftweb.sitemap.Loc._
import net.liftweb.common._

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
  override def supplimentalKidMenuItems: List[MenuItem] = calcDynItems() ::: super.supplimentalKidMenuItems
  init()
}