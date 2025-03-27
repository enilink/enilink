package net.enilink.platform.lift

import java.security.PrivilegedAction
import java.util.{Collections, Locale}
import javax.security.auth.Subject
import net.enilink.komma.core.URIs
import net.enilink.komma.model.ModelUtil
import net.enilink.komma.model.base.SimpleURIMapRule
import net.enilink.platform.core.{IContext, IContextProvider, ISession}
import net.enilink.platform.core.security.SecurityUtil
import net.enilink.platform.lift.sitemap.{Menus, ModelSpec, SiteMapXml}
import net.enilink.platform.lift.util.Globals
import net.liftweb.common.Box.box2Option
import net.liftweb.common.{Box, Empty, Full, Loggable}
import net.liftweb.http.LiftRulesMocker.toLiftRules
import net.liftweb.http.{LiftRules, ResourceServer, S}
import net.liftweb.sitemap.SiteMap
import net.liftweb.util.ClassHelpers
import org.osgi.framework.{Bundle, BundleContext, ServiceRegistration}
import org.osgi.service.component.annotations.{Activate, Component, Deactivate}
import org.slf4j.LoggerFactory

import java.util
import scala.jdk.CollectionConverters._

@Component(service = Array(classOf[LiftLifecycleManager]))
class LiftLifecycleManager extends Loggable {
	private var context: BundleContext = _

	private val log = LoggerFactory.getLogger(classOf[LiftLifecycleManager])

	private var bundleTracker: LiftBundleTracker = _

	private var contextServiceReg: ServiceRegistration[_] = _

	def bootBundle(bundle: Bundle, config: LiftBundleConfig) : Unit = {
		// add packages to search path
		config.packages filterNot (_.isEmpty) foreach (LiftRules.addToPackages(_))

		var models = List.empty[ModelSpec]
		for {
			xml <- config.sitemapXml
			xmlUrl <- Option(bundle.getResource(xml))
		} {
			val in = xmlUrl.openStream
			try {
				val siteMap = new SiteMapXml
				siteMap.parse(in)
				models = siteMap.models
				val menus = siteMap.menus
				config.sitemapMutator = Full(Menus.sitemapMutator(menus))
				// add context model selection rules to Globals
				siteMap.contextModelRules.foreach(rule => {
					Globals.contextModelRules.prepend(rule)
				})
			} catch {
				case e: java.lang.Exception => logger.warn("Lift-powered bundle " + bundle.getSymbolicName + " has invalid sitemap.", e)
			} finally {
				if (in != null) in.close()
			}
		}

		// load models as defined by sitemap XMLs
		if (models.nonEmpty) {
			Globals.contextModelSet.vend map { ms =>
				try {
					ms.getUnitOfWork.begin()
					models foreach { modelSpec =>
						val location = modelSpec.location.flatMap {
							case l if l.isRelative =>
								// resolve relative URIs against bundle
								Option(bundle.getResource(l.toString)).map(url => URIs.createURI(url.toString))
							case other => Some(other)
						}
						val uri = modelSpec.uri orElse {
							location.map { location =>
								val contentDescription = ModelUtil.determineContentDescription(location, ms.getURIConverter, Collections.emptyMap[Object, Object])
								val mimeType = ModelUtil.mimeType(contentDescription)
								// use the embedded ontology element as model URI
								val modelUri = ModelUtil.findOntology(ms.getURIConverter.createInputStream(location), "base:", mimeType)
								// simply use location as fallback
								if (modelUri == null) location else URIs.createURI(modelUri)
							}
						}
						uri.foreach { modelUri =>
							// add mapping rule if location is different from model URI
							for (l <- location if modelUri != l) {
								ms.getURIConverter.getURIMapRules.addRule(new SimpleURIMapRule(modelUri.toString, l.toString))
							}
							log.info("Creating model <{}>", modelUri)
							ms.createModel(modelUri)
						}
					}
				} finally {
					ms.getUnitOfWork.end()
				}
			}
		}

		// boot lift module
		config.module map { m =>
			try {
				try {
					ClassHelpers.createInvoker("boot", m) map (_ ())

					val moduleSitemapMutator = ClassHelpers.createInvoker("sitemapMutator", m).flatMap {
						f => f().map(_.asInstanceOf[SiteMap => SiteMap])
					}

					// combine the sitemap mutators
					config.sitemapMutator = (config.sitemapMutator, moduleSitemapMutator) match {
						case (Full(m1), Full(m2)) => Full(m1 andThen m2)
						case (m1@Full(_), _) => m1
						case _ => moduleSitemapMutator
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

	def bundles: util.Map[Bundle, LiftBundleConfig] = if (bundleTracker == null) {
		java.util.Collections.emptyMap[Bundle, LiftBundleConfig]
	} else {
		bundleTracker.getTracked
	}

	def initialize() : Unit = {
		val bundlesToStart = context.getBundles filter { bundle =>
			val headers = bundle.getHeaders
			val moduleStr = Box.legacyNullTest(headers.get("Lift-Module"))
			val packageStr = Box.legacyNullTest(headers.get("Lift-Packages"))
			moduleStr.isDefined || packageStr.isDefined
		}

		bundlesToStart filter (_ != context.getBundle) foreach { bundle =>
			try {
				bundle.start(Bundle.START_TRANSIENT)
			} catch {
				case e: Exception => // ignore
			}
		}

		bundleTracker = new LiftBundleTracker(context)
		bundleTracker.open()

		// allow duplicate link names
		SiteMap.enforceUniqueLinks = false

		// set context path
		LiftRules.calculateContextPath = () => Empty

		// Force the request to be UTF-8
		LiftRules.early.append(_.setCharacterEncoding("UTF-8"))

		val bundlesByStartLevel = bundles.entrySet.asScala.toSeq.sortBy(_.getValue.startLevel)
		bundlesByStartLevel foreach { entry =>
			// boot bundle as system user to allow modifications of RDF data
			Subject.doAs(SecurityUtil.SYSTEM_USER_SUBJECT, new PrivilegedAction[Unit]() {
				def run : Unit = {
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
				(prev, config) =>
					config.sitemapMutator match {
						case Full(m) => prev.andThen(m)
						case _ => prev
					}
			}
			siteMapMutator(SiteMap())
		} openOr SiteMap())

		contextServiceReg = context.registerService(
			classOf[IContextProvider],
			new IContextProvider {
				val session: ISession = new ISession {
					def getAttribute(name: String): AnyRef = S.session.flatMap(_.httpSession.map(_.attribute(name).asInstanceOf[AnyRef])) openOr null

					def setAttribute(name: String, value: AnyRef): Unit = S.session.foreach(_.httpSession.foreach(_.setAttribute(name, value)))

					def removeAttribute(name: String): Unit = S.session.foreach(_.httpSession.foreach(_.removeAttribute(name)))
				}
				val context: IContext = new IContext {
					def getSession: ISession = session

					def getLocale: Locale = S.locale
				}

				def get: IContext = S.session.flatMap(_.httpSession.map(_ => context)).orNull
			}, null)
	}

	@Activate
	def start(context: BundleContext) : Unit = {
		this.context = context
	}

	@Deactivate
	def stop(context: BundleContext) : Unit = {
		if (bundleTracker != null) {
			bundleTracker.close()
			bundleTracker = null
		}
		// shutdown configuration
		Globals.close

		if (contextServiceReg != null) {
			contextServiceReg.unregister()
			contextServiceReg = null
		}
	}
}