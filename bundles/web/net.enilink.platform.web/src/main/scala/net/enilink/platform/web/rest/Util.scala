package net.enilink.platform.web.rest

import net.enilink.platform.lift.util.Globals
import net.liftweb.http.Req
import net.liftweb.common.Empty
import net.enilink.komma.core.URIs
import net.enilink.komma.core.URI
import net.liftweb.common.Box
import net.liftweb.common.Full
import org.eclipse.rdf4j.query.resultio.QueryResultIO
import net.liftweb.http.ContentType
import org.eclipse.rdf4j.rio.Rio

object Util {
  def getModelUri(r: Req) = Globals.contextModel.vend.dmap(URIs.createURI(r.hostAndPath + r.uri): URI)(_.getURI)

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