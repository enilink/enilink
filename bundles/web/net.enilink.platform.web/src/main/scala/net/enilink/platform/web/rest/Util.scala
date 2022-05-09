package net.enilink.platform.web.rest

import net.enilink.komma.core.{URI, URIs}
import net.enilink.platform.lift.util.Globals
import net.liftweb.common.{Box, Empty, Full}
import net.liftweb.http.{ContentType, Req}
import org.eclipse.rdf4j.query.resultio.QueryResultIO
import org.eclipse.rdf4j.rio.Rio

object Util {
  def getModelUri(r: Req): URI = {
    // Globals.contextModel is full if the model already exists
    Globals.contextModel.vend.dmap{
      // model does not exists
      r.param("model") match {
        // use model parameter if it is specified
        case Full(name) if name.nonEmpty =>
          val uri = URIs.createURI(name)
          if (uri.isRelative) {
            throw new IllegalArgumentException(s"Invalid relative model URI '$uri'.")
          }
          uri
        // fall back to request URL as model name
        case _ => URIs.createURI(r.hostAndPath + r.uri)
      }
    }(_.getURI)
  }

  def getModel(modelUri: URI) = Globals.contextModelSet.vend flatMap { modelSet =>
    Box.legacyNullTest(modelSet.getModel(modelUri, false)) or {
      if (modelUri.fileExtension != null) Box.legacyNullTest(modelSet.getModel(modelUri.trimFileExtension, false)) else Empty
    }
  }

  def getOrCreateModel(modelUri: URI) = getModel(modelUri) or {
    Globals.contextModelSet.vend map { _.createModel(modelUri.trimFileExtension) }
  }

  def getSparqlQueryResponseMimeType(r: Req): Box[String] = {
    def toMimeType(ct: ContentType) = ct.theType + '/' + ct.subtype

    if (r.weightedAccept.isEmpty || r.acceptsStarStar) {
      Full("application/sparql-results+json")
    } else {
      r.weightedAccept.collectFirst {
        case ct if QueryResultIO.getWriterFormatForMIMEType(toMimeType(ct)).isPresent => toMimeType(ct)
        case ct if Rio.getWriterFormatForMIMEType(toMimeType(ct)).isPresent => toMimeType(ct)
      }
    }
  }
}