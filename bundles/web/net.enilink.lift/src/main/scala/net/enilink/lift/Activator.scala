package net.enilink.lift

import org.eclipse.equinox.http.servlet.ExtendedHttpService
import org.osgi.framework.BundleActivator
import org.osgi.framework.BundleContext
import org.osgi.framework.ServiceReference
import org.osgi.service.http.HttpContext
import org.osgi.service.http.HttpService
import org.osgi.util.tracker.ServiceTracker

import net.enilink.komma.http.KommaHttpPlugin
import javax.servlet.http.HttpServlet
import javax.servlet.FilterChain
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import net.liftweb.http.LiftFilter

class Activator extends BundleActivator {
  var httpServiceRef: ServiceReference[ExtendedHttpService] = null

  class HttpServiceTracker(context: BundleContext) extends ServiceTracker[ExtendedHttpService, ExtendedHttpService](context, classOf[ExtendedHttpService].getName, null) {
    class NoOpServlet extends HttpServlet

    var liftFilter: LiftFilter = new LiftFilter() {
      override def doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
        super.doFilter(req, res, chain)
      }
    }

    override def addingService(serviceRef: ServiceReference[ExtendedHttpService]) = {
      val httpService = super.addingService(serviceRef)
      if (httpService != null) {
        // create a default context to share between registrations
        val httpContext = httpService.createDefaultHttpContext()
        // required for HttpService to process the registered Lift filter
        // without a matching servlet the filter is silently ignored
        //        httpService.registerServlet("/lift", new NoOpServlet, null, httpContext)
        httpService.registerResources("/lift", "/", httpContext)
        httpService.registerFilter("/lift", liftFilter, null, httpContext)
      }
      httpService
    }

    override def removedService(serviceRef: ServiceReference[ExtendedHttpService], httpService: ExtendedHttpService) {
      httpService.unregisterFilter(liftFilter)
      httpService.unregister("/lift")
    }
  }

  var httpServiceTracker: HttpServiceTracker = null

  def start(context: BundleContext) {
    httpServiceTracker = new HttpServiceTracker(context)
    httpServiceTracker.open
  }

  def stop(context: BundleContext) {
    if (httpServiceTracker != null) {
      httpServiceTracker.close
    }
  }
}