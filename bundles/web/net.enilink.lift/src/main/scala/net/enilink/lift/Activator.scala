package net.enilink.lift

import java.util.concurrent.atomic.AtomicReference
import scala.Option.option2Iterable
import scala.collection.JavaConversions._
import org.eclipse.equinox.http.servlet.ExtendedHttpService
import org.osgi.framework.Bundle
import org.osgi.framework.BundleActivator
import org.osgi.framework.BundleContext
import org.osgi.framework.BundleEvent
import org.osgi.framework.ServiceReference
import org.osgi.framework.ServiceRegistration
import org.osgi.service.http.HttpContext
import org.osgi.util.tracker.BundleTracker
import org.osgi.util.tracker.ServiceTracker
import javax.servlet.FilterChain
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import net.enilink.core.ISession
import net.enilink.core.ISessionProvider
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.Full
import net.liftweb.common.Logger
import net.liftweb.http.LiftFilter
import net.liftweb.http.LiftRules
import net.liftweb.http.LiftRulesMocker.toLiftRules
import net.liftweb.http.S
import net.liftweb.osgi.OsgiBootable
import net.liftweb.sitemap.SiteMap
import net.liftweb.util.ClassHelpers
import net.enilink.lift.util.Globals
import java.io.File

object Activator {
  val PLUGIN_ID = "net.enilink.lift";
}

class Activator extends BundleActivator {
  private var bundleTracker: BundleTracker[LiftBundleConfig] = _
  private val httpServiceHolder = new AtomicReference[ExtendedHttpService]
  private var context: BundleContext = _

  private var sessionServiceReg: ServiceRegistration[_] = _

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

    sessionServiceReg = context.registerService(classOf[ISessionProvider],
      new ISessionProvider {
        val session = new ISession {
          def getAttribute(name: String) = S.session.flatMap(_.httpSession.map(_.attribute(name).asInstanceOf[AnyRef])) openOr null
          def setAttribute(name: String, value: AnyRef) = S.session.foreach(_.httpSession.foreach(_.setAttribute(name, value)))
          def removeAttribute(name: String) = S.session.foreach(_.httpSession.foreach(_.removeAttribute(name)))
        }
        def get = S.session.flatMap(_.httpSession.map(_ => session)) getOrElse null
      }, null)

    httpServiceTracker = new HttpServiceTracker(context)
    httpServiceTracker.open
  }

  def stop(context: BundleContext) {
    httpServiceHolder.get match {
      case null =>
      case httpService => {
        httpService.unregisterFilter(OsgiLiftFilter)
      }
    }
    if (httpServiceTracker != null) {
      httpServiceTracker.close
      httpServiceTracker = null
    }
    if (sessionServiceReg != null) {
      sessionServiceReg.unregister
      sessionServiceReg = null
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

    // any request that is not handled by a specific servlet is handled by Lift
    def isLiftRequest(req: HttpServletRequest) = req.getServletPath == ""
  }

  /**
   * Configuration of a Lift-powered bundle.
   */
  private case class LiftBundleConfig(packages: Seq[String]) {
    var sitemapMutator: Box[() => (SiteMap => SiteMap)] = None
    def mapResource(s: String) = s.replaceAll("//", "/")
  }

  /**
   * Special HttpContext that delegates resource lookups to observed
   * Lift-powered bundles and other methods to wrapped HttpContext.
   */
  private case class LiftHttpContext(context: HttpContext) extends HttpContext with Logger {
    val resourcePaths = System.getProperty("net.enilink.lift.resourcePaths") match {
      case null => Nil
      case paths => paths.split("\\s+\\s").map(new File(_)).toList
    }

    assert(context != null, "HttpContext must not be null!")

    override def getMimeType(s: String) = context getMimeType s

    override def getResource(s: String) = {
      debug("""Asked for resource "%s".""" format s)

      // also search common places by converting paths in the form of
      // /[application path]/some/resource to /some/resource
      val places = List(s) ++ (for (
        app <- Globals.application.vend;
        resourcePath = s.stripPrefix("/").split("/").toList;
        appPath = app.link.uriList if resourcePath.startsWith(appPath)
      ) yield resourcePath.drop(appPath.length).mkString("/", "/", ""))

      val liftBundles = bundleTracker.getTracked.entrySet.toSeq.view
      (places.view.flatMap { place =>
        liftBundles flatMap { b =>
          b.getKey getResource (b.getValue mapResource place) match {
            case null => None
            case res =>
              debug("""Lift-powered bundle "%s" answered for resource "%s".""".format(b.getKey.getSymbolicName, s))
              Some(res)
          }
        } headOption
      } headOption) orElse (
        // try to find resource at external location
        places.view.flatMap { place =>
          resourcePaths.view.flatMap { path =>
            val file = new File(path, place.stripPrefix("/"))
            if (file.exists) Some(file.toURI.toURL) else None
          }
        } headOption) match {
          case None => null
          case Some(res) => res
        }
    }

    override def handleSecurity(req: HttpServletRequest, res: HttpServletResponse) =
      context.handleSecurity(req, res)
  }
}