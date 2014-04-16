package net.enilink.lift.util

import net.liftweb.http.Factory
import java.util.regex.Pattern
import org.eclipse.core.runtime.Platform

object EnilinkRules extends Factory {
  val JAAS_CONFIG_URL = Platform.getBundle("net.enilink.core").getResource("/resources/jaas.conf");
  val REQUIRE_LOGIN = false || "true".equalsIgnoreCase(System.getProperty("enilink.loginrequired"))

  val LOGIN_METHODS = List(("eniLINK", "eniLINK"), ("IWU Share", "CMIS"), ("OpenID", "OpenID"))

  /**
   * The regular expression pattern for matching email addresses.
   */
  val emailRegexPattern = new FactoryMaker(Pattern.compile("^[a-z0-9._%\\-+]+@(?:[a-z0-9\\-]+\\.)+[a-z]{2,4}$", Pattern.CASE_INSENSITIVE)) {}

  /**
   * The regular expression pattern for matching user names.
   */
  val usernameRegexPattern = new FactoryMaker(Pattern.compile("^[a-z0-9](?:[._-]?[a-z0-9])+$", Pattern.CASE_INSENSITIVE)) {}
}