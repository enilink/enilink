package net.enilink.platform.lift.rest

import net.enilink.platform.lift.util.Globals
import net.liftweb.common.Loggable
import net.liftweb.http._
import net.liftweb.http.provider.HTTPCookie
import net.liftweb.http.rest.RestHelper
import net.liftweb.json.JsonDSL._
import net.liftweb.json._

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.Properties

import scala.util.Using

object FileService extends RestHelper with CorsHelper with Loggable {
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
      logger.debug("req.uploadedFiles.map{_.mimeType) is %s".format(req.uploadedFiles.map{_.mimeType}))
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
    case Nil Post req => saveAndRespond(req)
    case Nil Post AllowedMimeTypes(req) => saveAndRespond(req)
    case key :: Nil Get _ => serveFile(key)
    case key :: Nil Head _ => serveFile(key, head = true)
  })

  def serveFile(key: String, head: Boolean = false): LiftResponse = {
    val fileStore = Globals.fileStore.vend
    try {
      val size = fileStore.size(key)
      val props = fileStore.getProperties(key)
      val headers = responseHeaders ++ Option(props.getProperty("contentType")).map("Content-type" -> _).toList ++
        List("Content-length" -> size.toString) ++
        Option(props.getProperty("fileName")).map(name => ("Content-disposition", "attachment; filename=\"" + name + "\"")).toList
      if (head) new HeadResponse(size, headers, Nil, 200) else StreamingResponse(fileStore.openStream(key), () => {}, size, headers, Nil, 200)
    } catch {
      case _: IOException => NotFoundResponse("File does not exist.")
    }
  }

  def saveAndRespond(req: Req): LiftResponse = {
    try {
      // accept first entry from multipart/form-data content (if any)
      val fpo: Option[FileParamHolder] = req.uploadedFiles.headOption.map { fph =>
        logger.debug("saveAndRespond - multipart/form")
        // return param holder, contentType and fileName taken from attachment info
        fph
      }.orElse {
        // accept posted data from body, prefer HTTP inputstream
        // if already accessed, fall back to in-memory array
        req.rawInputStream.orElse(req.body).map { soa =>
          logger.debug("saveAndRespond - body/as-is")
          // use Content-Type and Slug headers for description
          val contentType = req.contentType.openOr("application/octet-stream")
          val fileName = req.header("Slug").openOr("unknown")
          soa match { // stream-or-array
            case is : InputStream =>
              // transfer data from stream to temporary file
              val tmpFile = File.createTempFile("upload-", ".tmp")
              Using(Files.newOutputStream(tmpFile.toPath)) { os =>
                logger.debug(s"""writing to $tmpFile""")
                is.transferTo(os)
                is.close
              }
              // create param holder for temporary file (like lift does for attachments)
              new OnDiskFileParamHolder("upload", contentType, fileName, tmpFile)
            case data : Array[Byte] =>
              // create param holder for data already loaded into memory
              FileParamHolder("memory", contentType, fileName, data)
            case _ => throw new Exception("illegal argument")
          }
        }
      }
      val jvalue: List[JValue] = fpo.map(saveFile(_)).getOrElse(List("size" -> 0L))
      JsonResponse(jvalue, responseHeaders, S.responseCookies, 200)
    } catch {
      case t: Throwable =>
        logger.error("saveAndRespond failed", t)
        val jerror = List(("status" -> "error") ~ ("message" -> s"""could not store file content: ${ t.getClass.getSimpleName } - ${ t.getMessage }"""))
        JsonResponse(jerror, responseHeaders, S.responseCookies, 500)
    }
  }

  def saveFile(fp: FileParamHolder): List[JValue] = {
    val fileStore = Globals.fileStore.vend
    val len = fp.length // evaluate length now, while file (if any) exists
    if (len == 0) {
      List("size" -> 0L)
    } else {
      val key = fp match {
        case fp: OnDiskFileParamHolder => fileStore.store(fp.localPath, true)
        case fp => fileStore.store(fp.file)
      }
      val props = new Properties
      props.setProperty("contentType", fp.mimeType)
      props.setProperty("fileName", fp.fileName)
      fileStore.setProperties(key, props)
      List(("id" -> key) ~ ("type" -> fp.mimeType) ~ ("size" -> len))
    }
  }
}
