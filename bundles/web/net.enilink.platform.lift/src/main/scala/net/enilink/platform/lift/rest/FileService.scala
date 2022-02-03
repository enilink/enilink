package net.enilink.platform.lift.rest

import net.enilink.platform.lift.util.Globals
import net.liftweb.common.Loggable
import net.liftweb.http._
import net.liftweb.http.provider.HTTPCookie
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JsonDSL._
import net.liftweb.json._

import java.io.IOException
import java.util.Properties

object FileService extends RestHelper with CorsHelper {
  /**
   * Simple response without content.
   */
  class HeadResponse(size: Long, headers: List[(String, String)], cookies: List[HTTPCookie], code: Int)
    extends OutputStreamResponse({
      _.close
    }, size: Long, headers: List[(String, String)], cookies: List[HTTPCookie], code: Int) {
  }

  /**
   * Extractor for an HTTP HEAD request.
   */
  protected object Head {
    def unapply(r: Req): Option[(List[String], Req)] = if (r.requestType.head_?) Some(r.path.partPath -> r) else None
  }

  object AllowedMimeTypes extends Loggable {
    def unapply(req: Req): Option[Req] = {
      logger.info("req.uploadedFiles.map{_.mimeType) is %s".format(req.uploadedFiles.map {
        _.mimeType
      }))
      req.uploadedFiles.flatMap {
        _.mimeType match {
          case _ => Some(req)
          // case _ => None
        }
      }.headOption
    }
  }

  serve("files" :: Nil prefix {
    case Nil Options _ => OkResponse()
    case Nil Post AllowedMimeTypes(req) => saveAndRespond(req)
    case key :: Nil Get _ => serveFile(key)
    case key :: Nil Head _ => serveFile(key, true)
  })

  def serveFile(key: String, head: Boolean = false): LiftResponse = {
    val fileStore = Globals.fileStore.vend
    try {
      val size = fileStore.size(key)
      val props = fileStore.getProperties(key)
      val headers = responseHeaders ++ Option(props.getProperty("contentType")).map(("Content-type" -> _)).toList ++
        List(("Content-length" -> size.toString)) ++
        Option(props.getProperty("fileName")).map(name => ("Content-disposition", "attachment; filename=\"" + name + "\"")).toList
      if (head) new HeadResponse(size, headers, Nil, 200) else StreamingResponse(fileStore.openStream(key), () => {}, size, headers, Nil, 200)
    } catch {
      case _: IOException => NotFoundResponse("File does not exist.")
    }
  }

  def saveAndRespond(req: Req): LiftResponse = {
    val jvalue: List[JValue] = req.uploadedFiles.flatMap(fph => saveFile(fph))
    JsonResponse(jvalue, responseHeaders, S.responseCookies, 200)
  }

  def saveFile(fp: FileParamHolder): List[JValue] = {
    val fileStore = Globals.fileStore.vend
    if (fp.length == 0) {
      List("size" -> 0L)
    } else {
      val key = fp match {
        case fp: OnDiskFileParamHolder => fileStore.store(fp.localFile.toPath, true)
        case fp => fileStore.store(fp.file)
      }
      val props = new Properties
      props.setProperty("contentType", fp.mimeType)
      props.setProperty("fileName", fp.fileName)
      fileStore.setProperties(key, props)
      List(("id" -> key) ~ ("type" -> fp.mimeType) ~ ("size" -> fp.length))
    }
  }
}
