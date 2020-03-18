package net.enilink.platform.lift.rdfa

import java.text.ParseException

/**
 * Path operations on URIs.
 *
 * Syntactic operations on URIs shouldn't be all intertwingled
 * with network operations like GET and POST, as
 * java.net.URL does. (oops! looks like I missed java.net.URI)
 *
 * There's no reason to get:
 *
 * Exception: java.net.MalformedURLException: unknown protocol: data
 *
 * when trying to combine a baseURI and a URI reference.
 *
 * References:
 *
 * <ul>
 *
 * <li><cite><a href="http://tools.ietf.org/html/rfc3986"
 * >Uniform Resource Identifier (URI): Generic Syntax</a></cite></li>
 *
 * <li><cite><a href="http://www.w3.org/DesignIssues/Model.html"
 * >The Web Model: Information hiding and URI syntax (Jan 98)</a></cite></li>
 *
 * <li><a href="http://lists.w3.org/Archives/Public/uri/2001Aug/0021.html"
 * >URI API design [was: URI Test Suite] Dan Connolly (Sun, Aug 12 2001)</a>
 * </li>
 * </ul>
 *
 */
object Util {

  /**
   * Appendix B. Parsing a URI Reference with a Regular Expression
   */
  val Parts = """(([^:/?#]+):)?(//([^/?#]*))?([^?#]*)(\?([^#]*))?(#(.*))?""".r

  /**
   * Combine URI reference with base URI
   * @param base an absolute URI
   * @param ref any URI
   *
   * @return per section 5. Reference Resolution of RFC3986
   *
   * @throws java.text.ParseException if base isn't an absoluteURI
   */
  def combine(base: String, ref: String): String = {
    val Parts(_, sr, _, ar, pr, _, qr, _, fragment) = ref

    if (sr != null) ref // ref is absolute; we're done.
    else {
      val Parts(_, scheme, _, ab, pb, _, qb, _, fb) = base
      if (scheme == null || scheme == "") {
        throw new ParseException("missing scheme in base URI" + base, 0)
      }

      val authority = if (ar == null) ab else ar

      assert(pr != null) // guaranteed by regex. worth testing, though.
      val path = if (pr == "") pb else {
        if (pr.startsWith("/")) pr else merge(ab, pb, pr)
      }

      val query = if (ar != null || pr != "" || qr != null) qr else qb

      // 5.3. Component Recomposition
      scheme + ":" + (
        if (authority != null) "//" + authority else "") + path + (
          if (query != null) "?" + query else "") + (
            if (fragment != null) "#" + fragment else "")
    }
  }

  /**
   * 5.2.3. Merge Paths
   */
  protected def merge(auth: String, pbase: String, pref: String): String = {
    assert(!pref.startsWith("/"))

    if (pbase == "") {
      if (auth != null) ("/" + pref) else pref
    } else {
      merge2(dirname(pbase), pref)
    }
  }

  protected def merge2(base: String, ref: String): String = {
    assert(base.endsWith("/"))
    if (ref.startsWith("./")) merge2(base, ref.substring(2)) else {
      if (ref.startsWith("../")) {
        val refup = ref.substring(3)
        if (base == "/") merge2(base, refup)
        else merge2(dirname(base.substring(0, base.length - 1)), refup)
      } else {
        base + ref
      }
    }
  }

  protected def dirname(path: String): String = {
    assert(path.contains("/"))
    path.substring(0, path.lastIndexOf('/') + 1)
  }
}