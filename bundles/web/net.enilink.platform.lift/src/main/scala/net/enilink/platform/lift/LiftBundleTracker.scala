package net.enilink.platform.lift

import org.osgi.framework.Bundle
import org.osgi.framework.BundleContext
import org.osgi.framework.BundleEvent
import org.osgi.util.tracker.BundleTracker

import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.LiftRules
import net.liftweb.util.ClassHelpers

class LiftBundleTracker(context: BundleContext) extends BundleTracker[LiftBundleConfig](context, Bundle.INSTALLED | Bundle.RESOLVED | Bundle.STARTING | Bundle.ACTIVE, null) with Loggable {
  override def addingBundle(bundle: Bundle, event: BundleEvent) = {
    val headers = bundle.getHeaders
    val siteMapStr = Box.legacyNullTest(headers.get("Lift-SiteMap"))
    val moduleStr = Box.legacyNullTest(headers.get("Lift-Module"))
    val packageStr = Box.legacyNullTest(headers.get("Lift-Packages"))

    if (siteMapStr.isDefined || moduleStr.isDefined || packageStr.isDefined) {
      if (!LiftRules.doneBoot) {
        // track bundle immediately
        val packages = packageStr.map(_.split("\\s,\\s")) openOr Array.empty
        val module = moduleStr.filter(_.trim.nonEmpty).flatMap { m =>
          try {
            val clazz = bundle loadClass m
            Full(clazz.newInstance.asInstanceOf[AnyRef])
          } catch {
            case cnfe: ClassNotFoundException =>
              logger.error("Lift-Module class " + m + " of bundle " + bundle.getSymbolicName + " could not be loaded.")
              Failure(cnfe.getMessage, Full(cnfe), Empty)
          }
        }
        module match {
          case f: Failure => null
          case m =>
            val startLevel = m.flatMap(ClassHelpers.createInvoker("startLevel", _)).flatMap(_()).map {
              case i: Int => i
              case o => o.toString.toInt
            }
            new LiftBundleConfig(module, packages, siteMapStr, startLevel openOr 0)
        }
      } else {
        // close the tracker immediately
        this.close
        LiftBootHelper.rebootLift
        null
      }
    } else null
  }

  override def removedBundle(bundle: Bundle, event: BundleEvent, config: LiftBundleConfig) {
    config.module.map { module =>
      try {
        // only shutdown bundles which where actually booted
        if (config.booted) {
          logger.debug("Stopping Lift-powered bundle " + bundle.getSymbolicName + ".")
          ClassHelpers.createInvoker("shutdown", module) map (_())
          logger.debug("Lift-powered bundle " + bundle.getSymbolicName + " stopped.")
        }
      } catch {
        case e: Throwable => logger.error("Error while stopping Lift-powered bundle " + bundle.getSymbolicName, e)
      }
    }
    if (context.getBundle(0).getState != Bundle.STOPPING) {
      // this is required if the module made changes to LiftRules or another global object
      // close the tracker immediately
      this.close
      LiftBootHelper.rebootLift
    }
  }

  override def modifiedBundle(bundle: Bundle, event: BundleEvent, config: LiftBundleConfig) {
  }
}