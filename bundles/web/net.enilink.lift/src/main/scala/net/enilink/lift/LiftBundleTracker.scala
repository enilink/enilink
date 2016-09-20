package net.enilink.lift

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions.seqAsJavaList

import org.osgi.framework.Bundle
import org.osgi.framework.BundleContext
import org.osgi.framework.BundleEvent
import org.osgi.framework.FrameworkUtil
import org.osgi.framework.wiring.FrameworkWiring
import org.osgi.util.tracker.BundleTracker

import net.enilink.core.IContextProvider
import net.liftweb.common.Box
import net.liftweb.common.Box.option2Box
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.LiftRules
import net.liftweb.util.ClassHelpers

class LiftBundleTracker(context: BundleContext) extends BundleTracker[LiftBundleConfig](context, Bundle.INSTALLED | Bundle.RESOLVED | Bundle.STARTING | Bundle.ACTIVE, null) with Loggable {
  var liftStarted = false
  var rebooting = false

  def liftStarted(liftStarted: Boolean) {
    this.liftStarted = liftStarted
  }

  override def addingBundle(bundle: Bundle, event: BundleEvent) = {
    val headers = bundle.getHeaders
    val moduleStr = Box.legacyNullTest(headers.get("Lift-Module"))
    val packageStr = Box.legacyNullTest(headers.get("Lift-Packages"))
    if (moduleStr.isDefined || packageStr.isDefined) {
      if (!liftStarted) {
        // track bundle immediately
        val packages = packageStr.map(_.split("\\s,\\s")) openOr Array.empty
        val module = moduleStr.filter(_.trim.nonEmpty).flatMap { m =>
          try {
            val clazz = bundle loadClass m
            Full(clazz.newInstance.asInstanceOf[AnyRef])
          } catch {
            case cnfe: ClassNotFoundException => logger.error("Lift-Module class " + m + " of bundle " + bundle.getSymbolicName + " not found."); None
          }
        }
        val startLevel = module flatMap { m => ClassHelpers.createInvoker("startLevel", m) flatMap (_()) } map {
          case i: Int => i
          case o => o.toString.toInt
        }
        val config = new LiftBundleConfig(module, packages, startLevel openOr 0)
        config
      } else {
        rebootLift
        null
      }
    } else null
  }

  def rebootLift {
    if (!rebooting) {
      logger.debug("Rebooting Lift")
      rebooting = true
      // reboot net.enilink.lift and dependent bundles with short delay
      Executors.newSingleThreadScheduledExecutor.schedule(new Runnable {
        def run {
          val liftBundle = FrameworkUtil.getBundle(classOf[LiftRules])
          val enilinkCore = FrameworkUtil.getBundle(classOf[IContextProvider])
          val systemBundle = context.getBundle(0)
          val frameworkWiring = systemBundle.adapt(classOf[FrameworkWiring])
          frameworkWiring.refreshBundles(enilinkCore :: liftBundle :: context.getBundle :: Nil)
        }
      }, 2, TimeUnit.SECONDS)
    }
  }

  override def removedBundle(bundle: Bundle, event: BundleEvent, config: LiftBundleConfig) {
    config.module.map { module =>
      try {
        logger.debug("Stopping Lift-powered bundle " + bundle.getSymbolicName + ".")
        ClassHelpers.createInvoker("shutdown", module) map (_())
        logger.debug("Lift-powered bundle " + bundle.getSymbolicName + " stopped.")
      } catch {
        case e: Throwable => logger.error("Error while stopping Lift-powered bundle " + bundle.getSymbolicName, e)
      }
      if (context.getBundle(0).getState != Bundle.STOPPING) {
        // this is required if the module made changes to LiftRules or another global object
        rebootLift
      }
    }
  }

  override def modifiedBundle(bundle: Bundle, event: BundleEvent, config: LiftBundleConfig) {
  }
}