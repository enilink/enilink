package net.enilink.platform.lift.rest

import net.liftweb.http.provider.HTTPCookie
import net.liftweb.http.{InMemoryResponse, LiftResponse, S}

/**
 * Mixin providing response headers for CORS scenarios.
 */
trait CorsHelper {
  val CORS_HEADERS = ("Access-Control-Allow-Origin", "*") :: //
    ("Access-Control-Allow-Credentials", "true") :: //
    ("Access-Control-Allow-Methods", "*") :: //
    ("Access-Control-Allow-Headers", "WWW-Authenticate,Keep-Alive,User-Agent,X-Requested-With,Cache-Control,Content-Type") :: Nil

  trait HeaderDefaults {
    val headers: List[(String, String)] = CORS_HEADERS ::: S.getResponseHeaders(Nil)
    val cookies: List[HTTPCookie] = S.responseCookies
  }

  /**
   * 400 Bad Request
   *
   * Your Request was missing an important element. Use this as a last resort if
   * the request appears incorrect. Use the `message` to indicate what was wrong
   * with the request, if that does not leak important information.
   */
  case class BadRequestResponse(message: String = "") extends LiftResponse with HeaderDefaults {
    def toResponse = InMemoryResponse(message.getBytes("UTF-8"), headers, cookies, 400)
  }

  /**
   * 403 Forbidden
   *
   * The server understood the request, but is refusing to fulfill it.
   * Authorization will not help and the request SHOULD NOT be repeated.
   */
  case class ForbiddenResponse(message: String) extends LiftResponse with HeaderDefaults {
    def toResponse = InMemoryResponse(message.getBytes("UTF-8"), "Content-Type" -> "text/plain; charset=utf-8" :: headers, cookies, 403)
  }

  /**
   * 500 Internal Server Error
   *
   * The server encountered an unexpected condition which prevented
   * it from fulfilling the request.
   */
  case class InternalServerErrorResponse() extends LiftResponse with HeaderDefaults {
    def toResponse = InMemoryResponse(Array(), headers, cookies, 500)
  }

  /**
   * 404 Not Found
   *
   * The server has not found anything matching the Request-URI.
   */
  case class NotFoundResponse(message: String) extends LiftResponse with HeaderDefaults {
    def toResponse = InMemoryResponse(message.getBytes("UTF-8"), "Content-Type" -> "text/plain; charset=utf-8" :: headers, cookies, 404)
  }

  /**
   * 200 response but without body.
   */
  case class OkResponse() extends LiftResponse with HeaderDefaults {
    def toResponse = InMemoryResponse(Array(), headers, cookies, 200)
  }

  /**
   * 415 Unsupported Media Type
   *
   * The server refuses to accept the request because the payload format is in an unsupported format.
   */
  case class UnsupportedMediaTypeResponse() extends LiftResponse with HeaderDefaults {
    def toResponse = InMemoryResponse(Array(), headers, cookies, 415)
  }

  def responseHeaders: List[(String, String)] = CORS_HEADERS ::: S.getResponseHeaders(Nil)

  def responseCookies: List[HTTPCookie] = S.responseCookies
}
