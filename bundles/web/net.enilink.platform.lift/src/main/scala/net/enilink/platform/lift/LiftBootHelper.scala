package net.enilink.platform.lift

import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}

import net.enilink.platform.core.IContextProvider
import net.liftweb.common.Loggable
import net.liftweb.http.LiftRules
import org.osgi.framework.{Bundle, FrameworkUtil}
import org.osgi.framework.wiring.FrameworkWiring
import org.osgi.service.http.HttpService

import scala.collection.JavaConversions._

object LiftBootHelper extends Loggable {
	var liftStopping : Boolean = false
	var rebootFuture : ScheduledFuture[_]  = null

	def rebootLift() : Unit = this.synchronized {
		if (rebootFuture == null) {
			// reboot liftweb and dependent bundles with short delay
			rebootFuture = Executors.newSingleThreadScheduledExecutor.schedule(new Runnable {
				def run {
					val liftBundle = FrameworkUtil.getBundle(classOf[LiftRules])
					if (liftBundle.getState == Bundle.ACTIVE) {
						logger.debug("Rebooting Lift")
						val enilinkCore = FrameworkUtil.getBundle(classOf[IContextProvider])
						val systemBundle = liftBundle.getBundleContext.getBundle(0)
						// HTTP service needs to be refreshed because else some weird bugs occur
						val httpService = FrameworkUtil.getBundle(classOf[HttpService])

						// refresh dependency closure
						val frameworkWiring = systemBundle.adapt(classOf[FrameworkWiring])
						frameworkWiring.refreshBundles(liftBundle :: enilinkCore :: httpService :: Nil)
					}
				}
			}, 1000, TimeUnit.MILLISECONDS)
		}
	}

	def liftBundleStopping(): Unit = this.synchronized {
		liftStopping = true
		if (rebootFuture != null) rebootFuture.cancel(false)
	}
}