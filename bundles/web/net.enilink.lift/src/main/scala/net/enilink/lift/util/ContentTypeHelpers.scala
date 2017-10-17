package net.enilink.lift.util

import net.enilink.komma.model.ModelPlugin
import org.eclipse.core.runtime.content.IContentType
import org.eclipse.core.runtime.QualifiedName
import org.eclipse.core.runtime.Platform
import net.liftweb.http.ContentType
import org.eclipse.core.runtime.content.IContentDescription

object ContentTypeHelpers {
  val mimeTypeProp = new QualifiedName(ModelPlugin.PLUGIN_ID, "mimeType")
  val hasWriterProp = new QualifiedName(ModelPlugin.PLUGIN_ID, "hasWriter")

  /**
   * Retrieve all registered RDF content types (those with a special mimeType property) and store them in a map.
   */
  val mimeType = "^(.+)/(.+)$".r

  lazy val rdfContentTypes: Map[(String, String), IContentType] = Platform.getContentTypeManager.getAllContentTypes.flatMap {
    contentType =>
      contentType.getDefaultDescription.getProperty(mimeTypeProp).asInstanceOf[String] match {
        case null => Nil
        case mimeType(superType, subType) => List((superType -> subType) -> contentType)
        case superType: String => List((superType -> "*") -> contentType)
        case _ => Nil
      }
  }.toMap

  /**
   * Find best matching content type for the given requested types.
   */
  def matchType(requestedTypes: List[ContentType]) = {
    object FindContentType {
      // extractor for partial function below
      def unapply(ct: ContentType) = rdfContentTypes.find(e => ct.matches(e._1))
    }
    requestedTypes.collectFirst { case FindContentType(key, value) => (key, value) }
  }

  /**
   * Find best matching content type for the suffix of the request URI.
   */
  def matchTypeByExtension(extension: String) = {
    rdfContentTypes.find(_._2.getFileSpecs(IContentType.FILE_EXTENSION_SPEC).contains(extension))
  }
  
  def isWritable(cd : IContentDescription) = "true".equals(String.valueOf(cd.getProperty(hasWriterProp)))
  
  def mimeType(cd : IContentDescription) = cd.getProperty(mimeTypeProp).toString
}