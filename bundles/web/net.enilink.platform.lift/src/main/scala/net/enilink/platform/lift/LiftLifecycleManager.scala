package net.enilink.platform.lift

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
import net.enilink.platform.core.IContext
import net.enilink.platform.core.IContextProvider
import net.enilink.platform.core.ISession
import net.enilink.platform.core.security.SecurityUtil
import net.enilink.platform.lift.util.Globals
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
import org.osgi.service.component.annotations.Component
import org.osgi.service.component.annotations.Activate
import org.osgi.service.component.annotations.Deactivate

@Component(
  immediate = true,
  service = Array(classOf[LiftLifecycleManager]))
class LiftLifecycleManager extends Loggable {
  private var bundleTracker: LiftBundleTracker = null

  private var contextServiceReg: ServiceRegistration[_] = _

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
          // mark bundle as booted
          config.booted = true
          logger.debug("Lift-powered bundle " + bundle.getSymbolicName + " booted.")
        } catch {
          case e: Throwable => logger.error("Error while booting Lift-powered bundle " + bundle.getSymbolicName, e)
        }
      } catch {
        case cnfe: ClassNotFoundException => // ignore
      }
    }
  }
  
  def bundles = bundleTracker.getTracked

  def initLift {
    // allow duplicate link names
    SiteMap.enforceUniqueLinks = false

    // set context path
    LiftRules.calculateContextPath = () => Empty

    // Force the request to be UTF-8
    LiftRules.early.append(_.setCharacterEncoding("UTF-8"))

    val bundlesByStartLevel = bundles.entrySet.toSeq.sortBy(_.getValue.startLevel)
    bundlesByStartLevel foreach { entry =>
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
      val siteMapMutator = bundlesByStartLevel.map(_.getValue).foldLeft((sm: SiteMap) => sm) {
        (prev, config) => config.sitemapMutator match { case Full(m) => prev.andThen(m) case _ => prev }
      }
      siteMapMutator(SiteMap())
    } openOr SiteMap())
  }

  @Activate
  def start(context: BundleContext) {
    val bundlesToStart = context.getBundles filter { bundle =>
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

    contextServiceReg = context.registerService(
      classOf[IContextProvider],
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
  }

  @Deactivate
  def stop(context: BundleContext) {
    if (bundleTracker != null) {
      bundleTracker.close
      bundleTracker = null
    }
    // shutdown configuration
    Globals.close

    if (contextServiceReg != null) {
      contextServiceReg.unregister
      contextServiceReg = null
    }
  }
}