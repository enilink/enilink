package net.enilink.platform.lift

import java.io.File
import java.net.URL

import scala.collection.mutable
import scala.collection.JavaConversions.asScalaSet

import org.osgi.util.tracker.BundleTracker
import org.webjars.WebJarAssetLocator

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import net.enilink.platform.lift.sitemap.Application
import net.enilink.platform.lift.util.Globals
import net.liftweb.common.Box.box2Iterable
import net.liftweb.common.Box.option2Box
import net.liftweb.common.Full
import net.liftweb.common.Logger
import net.liftweb.http.LiftRules
import net.liftweb.http.LiftRulesMocker.toLiftRules
import net.liftweb.http.ResourceServer
import net.liftweb.util.Helpers._
import org.osgi.framework.Bundle
import org.osgi.service.http.context.ServletContextHelper
import org.osgi.service.component.annotations.Component
import net.liftweb.http.LiftFilter
import net.liftweb.common.Box
import net.liftweb.osgi.OsgiBootable
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.FilterChain
import net.liftweb.util.Schedule
import org.osgi.service.component.annotations.Deactivate
import java.util.concurrent.ExecutorService
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.ComponentContext
import org.osgi.service.http.HttpService
import org.osgi.framework.ServiceRegistration
import javax.servlet.Filter
import org.osgi.service.component.annotations.Reference
import javax.servlet.http.HttpServlet
import javax.servlet.Servlet

/**
 * LiftFilter HTTP whiteboard component
 */
@Component(
  service = Array(classOf[Filter]),
  property = Array(
    "osgi.http.whiteboard.filter.name=LiftFilter",
    "osgi.http.whiteboard.filter.pattern=/*",
    "osgi.http.whiteboard.context.select=(osgi.http.whiteboard.context.name=liftweb)"
  ))
class LiftFilterComponent extends LiftFilter {
  private var liftServiceReg: ServiceRegistration[_] = _
  private var liftLifecycleManager: LiftLifecycleManager = null

  // ensure that lifecycle manager is initialized first
  @Reference
  def setLiftLifecycleManager(manager: LiftLifecycleManager) {
    this.liftLifecycleManager = manager
  }

  override def bootLift(loader: Box[String]) {
    super.bootLift(Full(classOf[OsgiBootable].getName))
  }

  override def doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
    super.doFilter(req, res, chain)
  }

  @Activate
  def activate(ctx: ComponentContext) {
    for {
      httpServiceRef <- Option(ctx.getBundleContext.getServiceReference(classOf[HttpService]))
      portKey <- httpServiceRef.getPropertyKeys.find(_.endsWith("http.port"))
      port <- Option(httpServiceRef.getProperty(portKey)).map(_.toString.toInt)
    } {
      liftServiceReg = ctx.getBundleContext.registerService(classOf[LiftService], new LiftService() {
        override def port() = port
      }, null)
    }
  }

  @Deactivate
  def shutdown {
    // access private schedule service field by reflection
    val serviceField = Schedule.getClass.getDeclaredFields.filter { f => f.getName == "service" || f.getName.endsWith("$$service") }.headOption
    val service = serviceField.map { f =>
      f.setAccessible(true)
      f.get(Schedule).asInstanceOf[ExecutorService]
    }

    // destroy lift servlet
    terminate

    // ensure that schedule service is really canceled
    // Lift calls only shutdown that does not cancel already enqueued tasks
    service.foreach(_.shutdownNow)

    if (liftServiceReg != null) {
      liftServiceReg.unregister
      liftServiceReg = null
    }
  }
}

/**
 * Instructs HTTP whiteboard to serve resources via an internal servlet
 */
@Component(
  service = Array(classOf[LiftResources]),
  property = Array(
    "osgi.http.whiteboard.resource.pattern=/*",
    "osgi.http.whiteboard.resource.prefix=/",
    "osgi.http.whiteboard.context.select=(osgi.http.whiteboard.context.name=liftweb)"))
class LiftResources {
}