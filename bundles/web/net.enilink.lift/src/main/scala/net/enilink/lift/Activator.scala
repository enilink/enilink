package net.enilink.lift

import java.io.File
import java.util.concurrent.atomic.AtomicReference

import scala.Option.option2Iterable
import scala.collection.JavaConversions.asScalaSet
import scala.collection.JavaConversions.collectionAsScalaIterable

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
import net.enilink.core.IContext
import net.enilink.core.IContextProvider
import net.enilink.core.ISession
import net.enilink.lift.sitemap.Application
import net.enilink.lift.util.Globals
import net.liftweb.common.Box
import net.liftweb.common.Box.box2Option
import net.liftweb.common.Box.option2Box
import net.liftweb.common.Empty
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.common.Logger
import net.liftweb.http.LiftFilter
import net.liftweb.http.LiftRules
import net.liftweb.http.LiftRulesMocker.toLiftRules
import net.liftweb.http.ResourceServer
import net.liftweb.http.S
import net.liftweb.osgi.OsgiBootable
import net.liftweb.sitemap.SiteMap
import net.liftweb.util.ClassHelpers

object Activator {
  val SERVICE_KEY_HTTP_PORT = "http.port"

  val PLUGIN_ID = "net.enilink.lift"
}

class Activator extends BundleActivator with Loggable {
  private var bundleTracker: BundleTracker[LiftBundleConfig] = _
  private val httpServiceHolder = new AtomicReference[ExtendedHttpService]
  private var context: BundleContext = _

  private var contextServiceReg: ServiceRegistration[_] = _
  private var liftServiceReg: ServiceRegistration[_] = _

  class HttpServiceTracker(context: BundleContext) extends ServiceTracker[ExtendedHttpService, ExtendedHttpService](context, classOf[ExtendedHttpService].getName, null) {
    override def addingService(serviceRef: ServiceReference[ExtendedHttpService]) = {
      val httpService = super.addingService(serviceRef)
      if ((httpServiceHolder getAndSet httpService) == null) {
        liftStarted = true

        // create a default context to share between registrations
        val httpContext = new LiftHttpContext(httpService.createDefaultHttpContext())
        httpService.registerResources("/", "/", httpContext)
        httpService.registerFilter("/", OsgiLiftFilter, null, httpContext)

        liftServiceReg = context.registerService(classOf[LiftService], new LiftService() {
          override def port() = {
            Integer.valueOf(serviceRef.getProperty(Activator.SERVICE_KEY_HTTP_PORT).toString())
          }
        }, null);
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
    bundleTracker = new BundleTracker[LiftBundleConfig](context, Bundle.RESOLVED | Bundle.STARTING | Bundle.ACTIVE | Bundle.STOPPING, null) with Logger {
      override def addingBundle(bundle: Bundle, event: BundleEvent) = {
        val headers = bundle.getHeaders
        val moduleStr = Box.legacyNullTest(headers.get("Lift-Module"))
        val packageStr = Box.legacyNullTest(headers.get("Lift-Packages"))
        if (moduleStr.isDefined || packageStr.isDefined) {
          val packages = packageStr.map(_.split("\\s,\\s")) openOr Array.empty
          if (!liftStarted) {
            // add packages to search path
            packages filterNot (_.isEmpty) foreach (LiftRules.addToPackages(_))
          }
          val module = moduleStr flatMap { m =>
            try {
              val clazz = bundle loadClass m
              Full(clazz.newInstance.asInstanceOf[AnyRef])
            } catch {
              case cnfe: ClassNotFoundException => error("Lift-Module class " + m + " of bundle " + bundle.getSymbolicName + " not found."); None
            }
          }
          val config = new LiftBundleConfig(module, packages)
          bootBundle(bundle, config)
          config
        } else null
      }

      override def removedBundle(bundle: Bundle, event: BundleEvent, config: LiftBundleConfig) {
        // TODO unboot bundle
      }

      def bootBundle(bundle: Bundle, config: LiftBundleConfig) {
        config.module map { m =>
          try {
            try {
              ClassHelpers.createInvoker("boot", m) map (_())
              ClassHelpers.createInvoker("sitemapMutator", m) map {
                f => config.sitemapMutator = Full(() => f().get.asInstanceOf[SiteMap => SiteMap])
              }
              debug("Lift-powered bundle " + bundle.getSymbolicName + " booted.")
            } catch {
              case e: Throwable => error("Error while booting Lift-powered bundle " + bundle.getSymbolicName, e)
            }
          } catch {
            case cnfe: ClassNotFoundException => // ignore
          }
        }
      }
    }
    bundleTracker.open

    initLift

    contextServiceReg = context.registerService(classOf[IContextProvider],
      new IContextProvider {
        val session = new ISession {
          def getAttribute(name: String) = S.session.flatMap(_.httpSession.map(_.attribute(name).asInstanceOf[AnyRef])) openOr null
          def setAttribute(name: String, value: AnyRef) = S.session.foreach(_.httpSession.foreach(_.setAttribute(name, value)))
          def removeAttribute(name: String) = S.session.foreach(_.httpSession.foreach(_.removeAttribute(name)))
        }
        val context = new IContext {
          def getSession = session
          def getLocale = S.locale
        }
        def get = S.session.flatMap(_.httpSession.map(_ => context)) getOrElse null
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
    if (contextServiceReg != null) {
      contextServiceReg.unregister
      contextServiceReg = null
    }
    if (liftServiceReg != null) {
      liftServiceReg.unregister
      liftServiceReg = null
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
  private case class LiftBundleConfig(module: Box[AnyRef], packages: Seq[String]) {
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

      def str[A](p: List[A]) = p.mkString("/", "/", "")
      def list(s: String) = s.stripPrefix("/").split("/").toList
      lazy val baseResourceLocation = list(ResourceServer.baseResourceLocation)
      val resourcePath = list(s)
      val places = if (resourcePath.startsWith(baseResourceLocation)) {
        val suffix = resourcePath.drop(1)
        // lookup possible appPath in sitemap
        val appPath = LiftRules.siteMap.flatMap(_.findLoc(suffix.head))
          .filter(_.params.contains(Application)).map(_.link.uriList) openOr Nil
        if (appPath.nonEmpty && suffix.startsWith(appPath))
          // /toserve/[application path]/some/resource => /toserve/some/resource
          List(s, str(baseResourceLocation ++ suffix.drop(appPath.length)))
        else List(s)
      } else Globals.application.vend match {
        case Full(app) if app.link.uriList.headOption.exists(_.nonEmpty) =>
          // search alternative places
          val resourcePath = list(s)
          val appPath = app.link.uriList
          if (resourcePath.startsWith(appPath))
            // /[application path]/some/resource => /some/resource
            List(s, str(resourcePath.drop(appPath.length)))
          else resourcePath match {
            case (prefix @ ("templates-hidden" | "resources-hidden")) :: (suffix @ _) =>
              if (suffix.startsWith(appPath)) {
                List(s, str(List(prefix) ++ suffix.drop(appPath.length)))
              } else {
                List(str(List(prefix) ++ appPath ++ suffix), s)
              }
            case _ =>
              // /some/resource => /[application path]/some/resource
              List(str(appPath ++ resourcePath), s)
          }
        // no application context
        case _ => List(s)
      }
      debug("""Places for resource "%s".""" format places)

      val liftBundles = bundleTracker.getTracked.entrySet.toSeq.view
      (places.view.flatMap { place =>
        liftBundles flatMap { b =>
          b.getKey getResource (b.getValue mapResource place) match {
            case null => None
            case res =>
              debug("""Lift-powered bundle "%s" answered for resource "%s".""".format(b.getKey.getSymbolicName, place))
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

    override def handleSecurity(req: HttpServletRequest, res: HttpServletResponse) = context.handleSecurity(req, res)
  }
}