package net.enilink.platform.lift.util

import net.liftweb.http.Factory
import java.util.regex.Pattern
import org.eclipse.core.runtime.Platform
import net.enilink.komma.core.URIs
import net.liftweb.common.Box
import java.net.URL
import scala.collection.JavaConversions._
import net.enilink.komma.core.IReference
import net.enilink.vocab.rdfs.RDFS
import net.liftweb.common.Empty
import net.liftweb.common.Full

object EnilinkRules extends Factory {

  val configURI = URIs.createURI("plugin://net.enilink.platform.lift/EnilinkRules/")

  val JAAS_CONFIG_URL = Globals.config.vend flatMap { cfg =>
    Box.legacyNullTest(cfg.filter(configURI, configURI.appendLocalPart("jaasConfigUrl"), null).objectString) map { new URL(_) }
  } openOr Platform.getBundle("net.enilink.platform.core").getResource("/resources/jaas.conf")

  val REQUIRE_LOGIN = false || "true".equalsIgnoreCase(System.getProperty("enilink.loginrequired"))

  val LOGIN_METHODS: List[(String, String)] = Globals.config.vend.toList flatMap { cfg =>
    cfg.filter(configURI, configURI.appendLocalPart("loginModule"), null).objects collect {
      case module: IReference =>
        val name = Box.legacyNullTest(cfg.filter(module, configURI.appendLocalPart("jaasConfigName"), null).objectString)
        val label = Box.legacyNullTest(cfg.filter(module, RDFS.PROPERTY_LABEL, null).objectString)
        name map { n =>
          (label openOr n, n)
        }
    } flatten
  }

  /**
   * The regular expression pattern for matching email addresses.
   */
  val emailRegexPattern = new FactoryMaker(Pattern.compile("^[a-z0-9._%\\-+]+@(?:[a-z0-9\\-]+\\.)+[a-z]{2,4}$", Pattern.CASE_INSENSITIVE)) {}

  /**
   * The regular expression pattern for matching user names.
   */
  val usernameRegexPattern = new FactoryMaker(Pattern.compile("^[a-z0-9](?:[._-]?[a-z0-9])+$", Pattern.CASE_INSENSITIVE)) {}
}