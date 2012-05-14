package net.enilink.lift.util

import net.enilink.komma.model.IModel
import net.enilink.lift.selection.SelectionProvider
import net.liftweb.http.Factory
import net.liftweb.util.Vendor.funcToVender
import net.liftweb.util.Helpers
import org.eclipse.core.runtime.Platform
import scala.Array.canBuildFrom
import net.liftweb.common._

/**
 * A registry for global variables which are shared throughout the application.
 */
object Globals extends Factory {
  implicit val time = new FactoryMaker(Helpers.now _) {}
  implicit val contextModel = new FactoryMaker(() => {Empty : Box[IModel]}) {}
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
  } : Box[AnyRef]) {}
}