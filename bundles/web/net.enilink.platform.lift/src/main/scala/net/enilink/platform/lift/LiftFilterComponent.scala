package net.enilink.platform.lift

import java.util.concurrent.ExecutorService

import javax.servlet.{Filter, FilterChain, FilterConfig, Servlet, ServletRequest, ServletResponse}
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletRequestWrapper
import javax.servlet.http.HttpServletResponse
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.{LiftFilter, LiftRules}
import net.liftweb.osgi.OsgiBootable
import net.liftweb.util.Schedule
import org.osgi.service.component.ComponentContext
import org.osgi.service.component.annotations.{Activate, Component, Deactivate, Reference, ServiceScope}
import org.osgi.service.http.HttpService

@Component(
  service = Array(classOf[LiftFilter])
)
class LiftFilterComponent extends LiftFilter with Loggable {
  @Reference
  var lcm : LiftLifecycleManager = _

  var config : FilterConfig = null
  @volatile var booted = false

  override def init(config: FilterConfig) : Unit = {
    this.config = config;
  }

  override def bootLift(loader: Box[String]) : Unit = {
    super.bootLift(Full(classOf[OsgiBootable].getName))
  }

  override def doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) : Unit = {
    // lazy initialize lift on first request
    if (!booted) {
      this.synchronized {
        // doFilter may be called in parallel by multiple requests
        if (!booted) {
          logger.debug("LiftFilterComponent::doFilter() - booting Lift")
          // boot lift bundles first
          lcm.initialize
          // start lift
          super.init(config)
          booted = true
        }
      }
    }
    super.doFilter(req, res, chain)
  }

  @Deactivate
  def deactivate : Unit = {
    logger.debug("LiftFilterComponent::deactivate()")

    // access private schedule service field by reflection
    val serviceField = Schedule.getClass.getDeclaredFields.filter { f => f.getName == "service" || f.getName.endsWith("$$service") }.headOption
    val service = serviceField.map { f =>
      f.setAccessible(true)
      f.get(Schedule).asInstanceOf[ExecutorService]
    }
    // ensure that schedule service is really canceled
    // Lift calls only shutdown that does not cancel already enqueued tasks
    service.foreach(_.shutdownNow)

    // this actually destroys the liftweb servlet
    super.destroy
  }

  override def destroy: Unit = {
    // nothing to do here
  }
}

/**
 * LiftFilter HTTP whiteboard component
 */
@Component(
  service = Array(classOf[Filter]),
  scope = ServiceScope.PROTOTYPE,
  property = Array(
    "osgi.http.whiteboard.filter.name=LiftFilter",
    "osgi.http.whiteboard.filter.servlet=LiftServlet",
    "osgi.http.whiteboard.filter.asyncSupported=true",
    "osgi.http.whiteboard.context.select=(osgi.http.whiteboard.context.name=liftweb)"))
class LiftFilterWrapper extends Filter with Loggable {
  @Reference
  var liftFilter : LiftFilter = _

  override def init(config: FilterConfig): Unit = {
    liftFilter.init(config)
  }

  override def doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) : Unit = {
    liftFilter.doFilter(req, res, chain)
  }

  override def destroy: Unit = {
    // nothing to do here
  }
}

/**
 * This servlet is required to enable async processing for the LiftFilter.
 * At least Equinox HTTP requires this additional servlet to work correctly with Lift's COMET.
 */
@Component(
  service = Array(classOf[Servlet]),
  scope = ServiceScope.PROTOTYPE,
  property = Array(
    "osgi.http.whiteboard.servlet.name=LiftServlet",
    "osgi.http.whiteboard.servlet.pattern=/",
    "osgi.http.whiteboard.servlet.asyncSupported=true",
    //"osgi.http.whiteboard.servlet.multipart.enabled=true",
    "osgi.http.whiteboard.context.select=(osgi.http.whiteboard.context.name=liftweb)"))
class LiftServletComponent extends HttpServlet with Loggable {
  override def doGet(req: HttpServletRequest, resp: HttpServletResponse) : Unit = {
    // this is a request for a static resource, other request are already handled by the lift filter
    logger.debug("LiftServletComponent::doGet(): " + req.getRequestURI)
    val pathInfo = req.getRequestURI
    val wrapped = new HttpServletRequestWrapper(req) {
      // re-use path info since it is otherwise lost due to the forwarding
      override def getPathInfo = pathInfo
    }
    // forward this to the internal whiteboard resources servlet as defined by LiftResources
    req.getRequestDispatcher("/lift-resources" + pathInfo).forward(wrapped, resp)
  }
}

/**
 * Instructs HTTP whiteboard to serve resources via an internal servlet
 */
@Component(
  service = Array(classOf[LiftResources]),
  property = Array(
    "osgi.http.whiteboard.resource.pattern=/lift-resources/*",
    "osgi.http.whiteboard.resource.prefix=/",
    "osgi.http.whiteboard.context.select=(osgi.http.whiteboard.context.name=liftweb)"))
class LiftResources {
}

/**
 * Registers the Lift service that helps clients to detect if the Lift framework is already running or not
 */
@Component( // add reference to HttpService because it is accessed in activate()
  reference = Array(
    new Reference(name = "HttpService", service = classOf[HttpService]),
    // the LiftService should only be available if LiftFilter is already registered
    new Reference(name = "LiftFilter", service = classOf[Filter], target = "(osgi.http.whiteboard.filter.name=LiftFilter)")))
class LiftServiceComponent extends LiftService {
  var port: Integer = -1

  @Activate
  def activate(ctx: ComponentContext) : Unit = {
    for {
      httpServiceRef <- Option(ctx.getBundleContext.getServiceReference(classOf[HttpService]))
      portKey <- httpServiceRef.getPropertyKeys.find(_.endsWith("http.port"))
      port <- Option(httpServiceRef.getProperty(portKey)).map(_.toString.toInt)
    } {
      LiftServiceComponent.this.port = port
    }
  }
}