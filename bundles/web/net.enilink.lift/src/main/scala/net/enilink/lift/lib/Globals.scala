package net.enilink.lift.lib

import scala.Array.canBuildFrom
import org.eclipse.core.runtime.Platform
import net.enilink.lift.selection.SelectionProvider
import net.liftweb.http.Factory
import net.liftweb.util.Vendor.funcToVender
import net.liftweb.util.Helpers
import net.enilink.komma.model.IModel

/**
 * A registry for global variables which are shared throughout the application.
 */
object Globals extends Factory {
  implicit val time = new FactoryMaker(Helpers.now _) {}
  implicit val contextModel = new FactoryMaker(() => {null : IModel}) {}
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
      case Array(first, _*) => first
      case _ => null
    }
  }) {}
}