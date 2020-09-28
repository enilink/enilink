package net.enilink.platform.lift.util

import java.util.regex.Pattern

import net.liftweb.http.Factory

object EnilinkRules extends Factory {
  /**
   * The regular expression pattern for matching email addresses.
   */
  val emailRegexPattern = new FactoryMaker(Pattern.compile("^[a-z0-9._%\\-+]+@(?:[a-z0-9\\-]+\\.)+[a-z]{2,4}$", Pattern.CASE_INSENSITIVE)) {}

  /**
   * The regular expression pattern for matching user names.
   */
  val usernameRegexPattern = new FactoryMaker(Pattern.compile("^[a-z0-9](?:[._-]?[a-z0-9])+$", Pattern.CASE_INSENSITIVE)) {}
}