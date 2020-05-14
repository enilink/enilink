package net.enilink.platform.lift

import java.util.concurrent.{Executors, TimeUnit}

import net.enilink.platform.core.IContextProvider
import net.liftweb.common.Loggable
import net.liftweb.http.LiftRules
import org.osgi.framework.FrameworkUtil
import org.osgi.framework.wiring.FrameworkWiring
import org.osgi.service.http.HttpService

import scala.collection.JavaConversions._

object LiftBootHelper extends Loggable {
	var rebooting = false

	def rebootLift {
		if (!rebooting) {
			logger.debug("Rebooting Lift")
			rebooting = true
			// reboot liftweb and dependent bundles with short delay
			Executors.newSingleThreadScheduledExecutor.schedule(new Runnable {
				def run {
					val liftBundle = FrameworkUtil.getBundle(classOf[LiftRules])
					val enilinkCore = FrameworkUtil.getBundle(classOf[IContextProvider])
					val systemBundle = liftBundle.getBundleContext.getBundle(0)
					// HTTP service needs to be refreshed because else some weird bugs occur
					val httpService = FrameworkUtil.getBundle(classOf[HttpService])

					// refresh dependency closure
					val frameworkWiring = systemBundle.adapt(classOf[FrameworkWiring])
					frameworkWiring.refreshBundles(liftBundle :: enilinkCore :: httpService :: Nil)
				}
			}, 500, TimeUnit.MILLISECONDS)
		}
	}
}