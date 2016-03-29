package net.enilink.lift

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.security.PrivilegedAction
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import scala.collection.JavaConversions._

import org.eclipse.equinox.http.servlet.ExtendedHttpService
import org.eclipse.osgi.service.datalocation.Location
import org.osgi.framework.Bundle
import org.osgi.framework.BundleActivator
import org.osgi.framework.BundleContext
import org.osgi.framework.BundleEvent
import org.osgi.framework.FrameworkUtil
import org.osgi.framework.ServiceReference
import org.osgi.framework.ServiceRegistration
import org.osgi.framework.wiring.FrameworkWiring
import org.osgi.util.tracker.BundleTracker
import org.osgi.util.tracker.ServiceTracker

import javax.security.auth.Subject
import javax.servlet.FilterChain
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import net.enilink.core.IContext
import net.enilink.core.IContextProvider
import net.enilink.core.ISession
import net.enilink.core.security.SecurityUtil
import net.enilink.lift.util.Globals
import net.liftweb.actor.LiftActor
import net.liftweb.common.Box
import net.liftweb.common.Box.box2Option
import net.liftweb.common.Box.option2Box
import net.liftweb.common.Empty
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import net.liftweb.http.LiftFilter
import net.liftweb.http.LiftRules
import net.liftweb.http.LiftRulesMocker.toLiftRules
import net.liftweb.http.ResourceServer
import net.liftweb.http.S
import net.liftweb.osgi.OsgiBootable
import net.liftweb.sitemap.SiteMap
import net.liftweb.util.ClassHelpers
import net.liftweb.util.Helpers
import net.liftweb.util.Schedule

object Activator {
  val SERVICE_KEY_HTTP_PORT = "http.port"

  val PLUGIN_ID = "net.enilink.lift"
}

class Activator extends BundleActivator with Loggable {
  private var bundleTracker: LiftBundleTracker = null
  private val httpServiceHolder = new AtomicReference[ExtendedHttpService]
  private var context: BundleContext = _

  private var contextServiceReg: ServiceRegistration[_] = _
  private var liftServiceReg: ServiceRegistration[_] = _
  private var liftFilter: OsgiLiftFilter = _

  private var dirWatcher: DirWatcher = _

  class HttpServiceTracker(context: BundleContext) extends ServiceTracker[ExtendedHttpService, ExtendedHttpService](context, classOf[ExtendedHttpService].getName, null) {
    override def addingService(serviceRef: ServiceReference[ExtendedHttpService]) = {
      val httpService = super.addingService(serviceRef)
      if ((httpServiceHolder getAndSet httpService) == null) {
        bundleTracker.liftStarted(true)
        liftFilter = new OsgiLiftFilter

        // create a default context to share between registrations
        val httpContext = new LiftHttpContext(httpService.createDefaultHttpContext(), bundleTracker)
        httpService.registerResources("/", "/", httpContext)
        httpService.registerFilter("/", liftFilter, null, httpContext)

        liftServiceReg = context.registerService(classOf[LiftService], new LiftService() {
          override def port() = {
            Integer.valueOf(serviceRef.getProperty(Activator.SERVICE_KEY_HTTP_PORT).toString)
          }
        }, null);
      }
      httpService
    }

    override def removedService(serviceRef: ServiceReference[ExtendedHttpService], httpService: ExtendedHttpService) {
      httpServiceHolder.compareAndSet(httpService, null)
    }
  }

  var httpServiceTracker: HttpServiceTracker = null

  def bootBundle(bundle: Bundle, config: LiftBundleConfig) {
    // add packages to search path
    config.packages filterNot (_.isEmpty) foreach (LiftRules.addToPackages(_))
    // boot lift module
    config.module map { m =>
      try {
        try {
          ClassHelpers.createInvoker("boot", m) map (_())
          config.sitemapMutator = ClassHelpers.createInvoker("sitemapMutator", m).flatMap {
            f => f().map(_.asInstanceOf[SiteMap => SiteMap])
          }
          logger.debug("Lift-powered bundle " + bundle.getSymbolicName + " booted.")
        } catch {
          case e: Throwable => logger.error("Error while booting Lift-powered bundle " + bundle.getSymbolicName, e)
        }
      } catch {
        case cnfe: ClassNotFoundException => // ignore
      }
    }
  }

  def initLift {
    // allow duplicate link names
    SiteMap.enforceUniqueLinks = false

    // set context path
    LiftRules.calculateContextPath = () => Empty

    // Force the request to be UTF-8
    LiftRules.early.append(_.setCharacterEncoding("UTF-8"))

    val bundles = bundleTracker.getTracked.entrySet.toSeq.sortBy(_.getValue.startLevel)
    bundles foreach { entry =>
      // boot bundle as system user to allow modifications of RDF data
      Subject.doAs(SecurityUtil.SYSTEM_USER_SUBJECT, new PrivilegedAction[Unit]() {
        def run {
          val bundle = entry.getKey
          bootBundle(bundle, entry.getValue)
        }
      })
    }

    // allow "classpath/webjars" to be served
    ResourceServer.allow {
      case "webjars" :: _ => true
    }

    // set the sitemap function
    // applies chained mutators from all lift bundles to an empty sitemap
    LiftRules.setSiteMapFunc(() => Box.legacyNullTest(bundleTracker).map { tracker =>
      val siteMapMutator = bundles.map(_.getValue).foldLeft((sm: SiteMap) => sm) {
        (prev, config) => config.sitemapMutator match { case Full(m) => prev.andThen(m) case _ => prev }
      }
      siteMapMutator(SiteMap())
    } openOr SiteMap())

    // attach to HTTP service
    httpServiceTracker = new HttpServiceTracker(context)
    httpServiceTracker.open
  }

  class DirWatcher(path: Path, reconciler: Bundle) extends LiftActor {
    import net.liftweb.util.Helpers._

    @volatile var open = true

    final val watchService = FileSystems.getDefault.newWatchService
    path.register(watchService, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)

    startCheck

    protected def messageHandler: PartialFunction[Any, Unit] = {
      case "check" => {
        watchService.poll match {
          case null =>
          case wk: WatchKey => {
            if (wk.pollEvents.nonEmpty) {
              logger.debug("Change of dropins detected - stopping " + reconciler.getSymbolicName)
              reconciler.stop(Bundle.STOP_TRANSIENT)
              // catch any exceptions here - TODO investigate why this throws some exceptions 
              tryo { reconciler.start(Bundle.START_TRANSIENT) }
              logger.debug("Change of dropins detected - restarted " + reconciler.getSymbolicName)
            }
            wk.reset
          }
        }
        if (open) startCheck
      }
    }

    def startCheck {
      Schedule.schedule(this, "check", 5 seconds)
    }

    def close {
      watchService.close
      open = false
    }
  }

  def watchDropins {
    def getEclipseHome = {
      val locService = context.getServiceReferences(classOf[Location], Location.ECLIPSE_HOME_FILTER).headOption.map(context.getService(_))
      locService.filterNot(_ == null).map { eclipseHome => Paths.get(eclipseHome.getURL.toURI) }
    }
    // register directory watcher for dropins
    val dropins = getEclipseHome.map(_.resolve("dropins")).filter { p => Files.exists(p) }
    val reconciler = context.getBundles.filter(_.getSymbolicName == "org.eclipse.equinox.p2.reconciler.dropins").headOption
    for (r <- reconciler; p <- dropins) {
      logger.info("Dropins directory: " + p)
      dirWatcher = new DirWatcher(p, r)
    }
  }

  def start(context: BundleContext) {
    this.context = context

    val bundlesToStart = this.context.getBundles filter { bundle =>
      val headers = bundle.getHeaders
      val moduleStr = Box.legacyNullTest(headers.get("Lift-Module"))
      val packageStr = Box.legacyNullTest(headers.get("Lift-Packages"))
      moduleStr.isDefined || packageStr.isDefined
    }

    bundlesToStart filter (_ != context.getBundle) foreach { bundle =>
      bundle.start(Bundle.START_TRANSIENT)
    }

    bundleTracker = new LiftBundleTracker(context)
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

    watchDropins
  }

  def stop(context: BundleContext) {
    if (dirWatcher != null) {
      dirWatcher = null
    }
    if (bundleTracker != null) {
      bundleTracker.close
      bundleTracker = null
    }
    // shutdown configuration
    Globals.close
    if (liftServiceReg != null) {
      liftServiceReg.unregister
      liftServiceReg = null
    }
    if (contextServiceReg != null) {
      contextServiceReg.unregister
      contextServiceReg = null
    }
    if (liftFilter != null) {
      httpServiceHolder.get match {
        case null =>
        case httpService => {
          httpService.unregisterFilter(liftFilter)
        }
      }
      liftFilter.shutdown
      liftFilter = null
    }
    if (httpServiceTracker != null) {
      httpServiceTracker.close
      httpServiceTracker = null
    }
    this.context = null
  }

  /**
   * Special LiftFilter for lift-osgi bundle: Set OsgiBootable.
   */
  private class OsgiLiftFilter extends LiftFilter {
    override def bootLift(loader: Box[String]) {
      super.bootLift(Full(classOf[OsgiBootable].getName))
    }

    override def doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
      if (isLiftRequest(req.asInstanceOf[HttpServletRequest])) super.doFilter(req, res, chain) else chain.doFilter(req, res)
    }

    // any request that is not handled by a specific servlet is handled by Lift
    def isLiftRequest(req: HttpServletRequest) = req.getServletPath == ""

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
    }
  }
}