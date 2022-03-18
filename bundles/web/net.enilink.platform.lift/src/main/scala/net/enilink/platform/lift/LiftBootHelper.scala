package net.enilink.platform.lift

import net.enilink.platform.core.IContextProvider
import net.liftweb.common.Loggable
import net.liftweb.http.LiftRules
import org.osgi.framework.FrameworkUtil
import org.osgi.framework.wiring.FrameworkWiring

import scala.jdk.CollectionConverters._

object LiftBootHelper extends Loggable {
	var rebooting: Boolean = false

	def rebootLift(): Unit = this.synchronized {
		if (!rebooting) {
			rebooting = true
			// reboot liftweb and dependent bundles with short delay
			logger.debug("Rebooting Lift")
			val liftBundle = FrameworkUtil.getBundle(classOf[LiftRules])
			val enilinkCore = FrameworkUtil.getBundle(classOf[IContextProvider])
			val systemBundle = liftBundle.getBundleContext.getBundle(0)

			// refresh dependency closure
			val frameworkWiring = systemBundle.adapt(classOf[FrameworkWiring])
			frameworkWiring.refreshBundles(List(liftBundle, enilinkCore).asJava)
		}
	}
}
