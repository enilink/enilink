package net.enilink.lift

import java.io.File
import java.net.URL
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

import scala.Option.option2Iterable
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
import org.osgi.service.http.HttpContext
import org.osgi.util.tracker.BundleTracker
import org.osgi.util.tracker.ServiceTracker
import org.webjars.WebJarAssetLocator

import javax.security.auth.Subject
import javax.servlet.FilterChain
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import net.enilink.core.IContext
import net.enilink.core.IContextProvider
import net.enilink.core.ISession
import net.enilink.core.security.SecurityUtil
import net.enilink.lift.sitemap.Application
import net.enilink.lift.util.Globals
import net.liftweb.actor.LiftActor
import net.liftweb.common.Box
import net.liftweb.common.Box.box2Iterable
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
        liftStarted = true
        liftFilter = new OsgiLiftFilter

        // create a default context to share between registrations
        val httpContext = new LiftHttpContext(httpService.createDefaultHttpContext())
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

  class LiftBundleTracker extends BundleTracker[LiftBundleConfig](this.context, Bundle.INSTALLED | Bundle.RESOLVED | Bundle.STARTING | Bundle.STOPPING | Bundle.ACTIVE, null) with Loggable {
    var rebooting = false
    override def addingBundle(bundle: Bundle, event: BundleEvent) = {
      val headers = bundle.getHeaders
      val moduleStr = Box.legacyNullTest(headers.get("Lift-Module"))
      val packageStr = Box.legacyNullTest(headers.get("Lift-Packages"))
      if (moduleStr.isDefined || packageStr.isDefined) {
        if (!liftStarted) {
          logger.info("Lift module: " + bundle.getSymbolicName + " (" + bundle.getVersion + ")")
          // track bundle immediately
          val packages = packageStr.map(_.split("\\s,\\s")) openOr Array.empty
          val module = moduleStr.filter(_.trim.nonEmpty).flatMap { m =>
            try {
              val clazz = bundle loadClass m
              Full(clazz.newInstance.asInstanceOf[AnyRef])
            } catch {
              case cnfe: ClassNotFoundException => logger.error("Lift-Module class " + m + " of bundle " + bundle.getSymbolicName + " not found."); None
            }
          }
          val startLevel = module flatMap { m => ClassHelpers.createInvoker("startLevel", m) flatMap (_()) } map {
            case i: Int => i
            case o => o.toString.toInt
          }
          val config = new LiftBundleConfig(module, packages, startLevel openOr 0)
          config
        } else {
          rebootLift
          null
        }
      } else null
    }

    def rebootLift {
      if (!rebooting) {
        logger.debug("Rebooting Lift")
        rebooting = true
        // reboot net.enilink.lift and dependent bundles with short delay
        Executors.newSingleThreadScheduledExecutor.schedule(new Runnable {
          def run {
            val liftBundle = FrameworkUtil.getBundle(classOf[LiftRules])
            val enilinkCore = FrameworkUtil.getBundle(classOf[IContextProvider])
            val systemBundle = context.getBundle(0)
            val frameworkWiring = systemBundle.adapt(classOf[FrameworkWiring])
            frameworkWiring.refreshBundles(enilinkCore :: liftBundle :: context.getBundle :: Nil)
          }
        }, 2, TimeUnit.SECONDS)
      }
    }

    override def removedBundle(bundle: Bundle, event: BundleEvent, config: LiftBundleConfig) {
    }

    override def modifiedBundle(bundle: Bundle, event: BundleEvent, config: LiftBundleConfig) {
      val systemShutdown = context.getBundle(0).getState == Bundle.STOPPING
      if (event.getType == BundleEvent.STOPPING) {
        config.module.map { module =>
          try {
            logger.debug("Stopping Lift-powered bundle " + bundle.getSymbolicName + ".")
            ClassHelpers.createInvoker("shutdown", module) map (_())
            logger.debug("Lift-powered bundle " + bundle.getSymbolicName + " stopped.")
          } catch {
            case e: Throwable => logger.error("Error while stopping Lift-powered bundle " + bundle.getSymbolicName, e)
          }
          // this is required if the module made changes to LiftRules or another global object
          rebootLift
        }
      }
    }
  }

  var liftStarted = false
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

    bundleTracker = new LiftBundleTracker
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

  /**
   * Configuration of a Lift-powered bundle.
   */
  case class LiftBundleConfig(module: Box[AnyRef], packages: Seq[String], startLevel: Int) {
    var sitemapMutator: Box[SiteMap => SiteMap] = None
    def mapResource(s: String) = s.replaceAll("//", "/")
  }

  /**
   * Special HttpContext that delegates resource lookups to observed
   * Lift-powered bundles and other methods to wrapped HttpContext.
   */
  private case class LiftHttpContext(context: HttpContext) extends HttpContext with Logger {
    assert(context != null, "HttpContext must not be null!")

    // support for webjars
    val webjarAssets = new java.util.HashSet[String]
    bundleTracker.getTracked.entrySet.toSeq foreach { entry =>
      val bundle = entry.getKey
      val hasWebJars = bundle.getEntry(WebJarAssetLocator.WEBJARS_PATH_PREFIX) != null
      if (hasWebJars) {
        val assets = bundle.findEntries(WebJarAssetLocator.WEBJARS_PATH_PREFIX, "*", true)
        if (assets != null) {
          while (assets.hasMoreElements) webjarAssets.add(assets.nextElement.toString)
        }
      }
    }
    val webJarLocator = new WebJarAssetLocator(webjarAssets)
    val WEBJARS = "/" + ResourceServer.baseResourceLocation + "/webjars"

    // external resource locations
    val resourcePaths = System.getProperty("net.enilink.lift.resourcePaths") match {
      case null => Nil
      case paths => paths.split("\\s+\\s").map(new File(_)).toList
    }

    override def getMimeType(s: String) = context getMimeType s

    override def getResource(s: String) = {
      debug("""Asked for resource "%s".""" format s)

      val path = if (s.startsWith("/")) s else "/" + s
      if (path.startsWith(WEBJARS)) {
        // try to serve webjars resource
        val assetPath = s.substring(WEBJARS.length + 1)
        val url = webJarLocator.getFullPath(assetPath)
        // assetPath is a "bundleentry://" or "bundleresource://" URL
        new URL(url)
      } else {
        // serve other resource
        defaultResource(s)
      }
    }

    def defaultResource(s: String): URL = {
      def str[A](p: List[A]) = p.mkString("/", "/", "")
      def list(s: String) = s.stripPrefix("/").split("/").toList
      lazy val baseResourceLocation = list(ResourceServer.baseResourceLocation)
      val resourcePath = list(s)
      val places = if (resourcePath.startsWith("star" :: Nil)) {
        // star stands for any application
        (Globals.application.vend match {
          // /star/some/resource => [application path]/some/resource
          case Full(app) if app.path.headOption.exists(_.nonEmpty) => List(str(app.path ++ resourcePath.tail))
          case _ => Nil
          // /star/some/resource => /some/resource
        }) ++ List(str(resourcePath.tail))
      } else if (resourcePath.startsWith(baseResourceLocation)) {
        val suffix = resourcePath.drop(1)
        // lookup possible appPath in sitemap
        val appPath = LiftRules.siteMap.flatMap(_.findLoc(suffix.head).collectFirst(_.currentValue match {
          case Full(app: Application) => app.path
        })) openOr Nil
        if (appPath.nonEmpty && suffix.startsWith(appPath))
          // /toserve/[application path]/some/resource => /toserve/some/resource
          List(s, str(baseResourceLocation ++ suffix.drop(appPath.length)))
        else List(s)
      } else Globals.application.vend match {
        case Full(app) if app.path.headOption.exists(_.nonEmpty) =>
          // search alternative places
          val appPath = app.path
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
        liftBundles.flatMap { b =>
          b.getKey getResource (b.getValue mapResource place) match {
            case null => None
            case res =>
              debug("""Lift-powered bundle "%s" answered for resource "%s".""".format(b.getKey.getSymbolicName, place))
              Some(res)
          }
        }.headOption
      }.headOption) orElse (
        // try to find resource at external location
        places.view.flatMap { place =>
          resourcePaths.view.flatMap { path =>
            val file = new File(path, place.stripPrefix("/"))
            if (file.exists) Some(file.toURI.toURL) else None
          }
        }.headOption) match {
          case None => null
          case Some(res) => res
        }
    }

    override def handleSecurity(req: HttpServletRequest, res: HttpServletResponse) = context.handleSecurity(req, res)
  }
}