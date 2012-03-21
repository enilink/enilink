package net.enilink.lift

import java.util.concurrent.atomic.AtomicReference

import scala.Option.option2Iterable
import scala.collection.JavaConversions._

import org.eclipse.equinox.http.servlet.ExtendedHttpService
import org.osgi.framework.ServiceReference
import org.osgi.framework.Bundle
import org.osgi.framework.BundleActivator
import org.osgi.framework.BundleContext
import org.osgi.framework.BundleEvent
import org.osgi.service.http.HttpContext
import org.osgi.util.tracker.BundleTracker
import org.osgi.util.tracker.ServiceTracker

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.FilterChain
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Full
import net.liftweb.common.Logger
import net.liftweb.http.LiftRulesMocker.toLiftRules
import net.liftweb.http.LiftFilter
import net.liftweb.http.LiftRules
import net.liftweb.osgi.OsgiBootable
import net.liftweb.sitemap.SiteMap
import net.liftweb.util.ClassHelpers

class Activator extends BundleActivator {
  private var bundleTracker: BundleTracker[LiftBundleConfig] = _
  private val httpServiceHolder = new AtomicReference[ExtendedHttpService]
  private var context : BundleContext = _

  class HttpServiceTracker(context: BundleContext) extends ServiceTracker[ExtendedHttpService, ExtendedHttpService](context, classOf[ExtendedHttpService].getName, null) {
    override def addingService(serviceRef: ServiceReference[ExtendedHttpService]) = {
      val httpService = super.addingService(serviceRef)
      if ((httpServiceHolder getAndSet httpService) == null) {
        liftStarted = true

        // create a default context to share between registrations
        val httpContext = new LiftHttpContext(httpService.createDefaultHttpContext())
        httpService.registerResources("/", "/", httpContext)
        httpService.registerFilter("/", OsgiLiftFilter, null, httpContext)
      }
      httpService
    }

    override def removedService(serviceRef: ServiceReference[ExtendedHttpService], httpService: ExtendedHttpService) {
      httpServiceHolder.compareAndSet(httpService, null)
    }
  }

  var liftStarted = false
  var httpServiceTracker: HttpServiceTracker = null

  def initLift {
    // allow duplicate link names
    SiteMap.enforceUniqueLinks = false
    
    // set context path
    LiftRules.calculateContextPath = () => Empty

    // Force the request to be UTF-8
    LiftRules.early.append(_.setCharacterEncoding("UTF-8"))

    // set the sitemap function
    // applies chained mutators from all lift bundles to an empty sitemap
    LiftRules.setSiteMapFunc(() => {
      val siteMapMutator = bundleTracker.getTracked.values.foldLeft((sm: SiteMap) => sm)(
        (prev, config) => config.sitemapMutator match { case Full(m) => prev.andThen(m()) case _ => prev })
      siteMapMutator(SiteMap())
    })
  }
  
  def start(context: BundleContext) {
    this.context = context
    initLift

    bundleTracker = new BundleTracker[LiftBundleConfig](context, Bundle.RESOLVED | Bundle.STARTING | Bundle.ACTIVE, null) with Logger {
      override def addingBundle(bundle: Bundle, event: BundleEvent) = {
        bundle.getHeaders.get("Lift-Packages") match {
          case packageStr: String => {
            val packages = packageStr.split("\\s,\\s")
            if (!liftStarted) {
              // add packages to search path
              packages filterNot (_.isEmpty) foreach (LiftRules.addToPackages(_))
            }
            val config = new LiftBundleConfig(packages)
            bootBundle(bundle, config)

            config
          }
          case _ => null
        }
      }

      override def removedBundle(bundle: Bundle, event: BundleEvent, config: LiftBundleConfig) {
        // TODO unboot bundle
      }

      def bootBundle(bundle: Bundle, config: LiftBundleConfig) {
        // Boot
        try {
          val clazz = bundle loadClass "bootstrap.liftweb.LiftModule"
          val bootInvoker = ClassHelpers.createInvoker("boot", clazz.newInstance.asInstanceOf[AnyRef])
          bootInvoker map (_())

          val sitemapMutatorInvoker = ClassHelpers.createInvoker("sitemapMutator", clazz.newInstance.asInstanceOf[AnyRef])
          sitemapMutatorInvoker map {
            f => config.sitemapMutator = Full(() => f().get.asInstanceOf[SiteMap => SiteMap])
          }
        } catch {
          case cnfe: ClassNotFoundException => // ignore
        }
        debug("Lift-powered bundle " + bundle.getSymbolicName + " booted.")
      }
    }
    bundleTracker.open

    httpServiceTracker = new HttpServiceTracker(context)
    httpServiceTracker.open
  }

  def stop(context: BundleContext) {
    httpServiceHolder.get match {
      case null =>
      case httpService => {
        httpService.unregisterFilter(OsgiLiftFilter)
        httpService.unregister("/lift")
      }
    }
    if (httpServiceTracker != null) {
      httpServiceTracker.close
      httpServiceTracker = null
    }
    if (bundleTracker != null) {
      bundleTracker.close
      bundleTracker = null
    }
    this.context = null
  }

  /**
   * Special LiftFilter for lift-osgi bundle: Set OsgiBootable.
   */
  private object OsgiLiftFilter extends LiftFilter {
    override def bootLift(loader: Box[String]) {
      super.bootLift(Full(classOf[OsgiBootable].getName))
    }
    override def doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
      if (isLiftRequest(req.asInstanceOf[HttpServletRequest])) super.doFilter(req, res, chain) else chain.doFilter(req, res)
    }

    val pathStartsWith = "/([^/]+).*".r
    def isLiftRequest(req: HttpServletRequest) = {
      val path = req.getContextPath + req.getServletPath
      path match {
        // do not handle known application contexts
        case pathStartsWith("enilink" | "rwt-resources" | "eclipse-resources") => false
        case _ => true
      }
    }
  }

  /**
   * Configuration of a Lift-powered bundle.
   */
  private case class LiftBundleConfig(packages: Seq[String]) {
    var sitemapMutator: Box[() => (SiteMap => SiteMap)] = None
    def mapResource(s: String) = s.replaceAll("//", "/")
  }

  /**
   * Special HttpContext that delegates resource lookups to observerd
   * Lift-powered bundles and other methods to wrapped HttpContext.
   */
  private case class LiftHttpContext(context: HttpContext) extends HttpContext with Logger {
    assert(context != null, "HttpContext must not be null!")

    override def getMimeType(s: String) = context getMimeType s

    override def getResource(s: String) = {
      debug("""Asked for resource "%s".""" format s)
      val liftBundles = bundleTracker.getTracked.entrySet.toSeq.projection
      // TODO: The following probably could be done better!
      liftBundles flatMap {
        liftBundle =>
          liftBundle.getKey getResource (liftBundle.getValue mapResource s) match {
            case null => None
            case res =>
              debug("""Lift-powered bundle "%s" answered for resource "%s".""".format(liftBundle.getKey.getSymbolicName, s))
              Some(res)
          }
      } headOption match {
        case None => null
        case Some(res) => res
      }
    }

    override def handleSecurity(req: HttpServletRequest, res: HttpServletResponse) =
      context.handleSecurity(req, res)
  }
}