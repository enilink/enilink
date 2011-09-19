package net.enilink.lift

import java.util.Hashtable
import org.osgi.framework.BundleActivator
import org.osgi.framework.BundleContext
import net.enilink.komma.http.KommaHttpPlugin
import org.osgi.framework.ServiceReference
import org.osgi.service.http.HttpService
import org.ops4j.pax.web.service.WebContainer
import net.liftweb.http.LiftFilter
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.FilterChain
import net.enilink.lift.eclipse.SelectionHolder

class Activator extends BundleActivator {
  var httpServiceRef: ServiceReference[WebContainer] = null

  def start(context: BundleContext) {
    KommaHttpPlugin.getDefault

    httpServiceRef = context.getServiceReference(classOf[WebContainer])

    if (httpServiceRef != null) {
      val httpService = context.getService(httpServiceRef)

      if (httpService != null) {
        // create a default context to share between registrations
        val httpContext = httpService.createDefaultHttpContext()
        httpService.registerResources("/", "/", httpContext)
        httpService.registerFilter(new LiftFilter() {
          override def doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain)  {
        	  super.doFilter(req, res, chain)
          }
        }, Array("/*"), null, null, httpContext)
      }
      
      SelectionHolder.init
    }
  }

  def stop(context: BundleContext) {
    if (httpServiceRef != null) {
      try {
        val service = context.getService(httpServiceRef)
        //        service.unregister("/helloworld/hs");
      } catch {
        case e: Exception => System.out.println(e.getMessage())
      } finally {
        context.ungetService(httpServiceRef)
        httpServiceRef = null
      }
    }
  }
}
