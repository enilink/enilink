package net.enilink.lift.snippet
import scala.collection.JavaConversions._
import scala.xml.NodeSeq
import net.enilink.komma.core.IQuery
import net.enilink.lift.eclipse.SelectionHolder
import net.liftweb.util.Helpers._
import net.liftweb.util._
import net.enilink.komma.core.IReference
import net.enilink.komma.model.IObject

class Rdf {
  def render = "* *" #> renderResource

  def renderResource = (n: NodeSeq) => {
    val sparql = n.text

    SelectionHolder.getSelection() match {
      case resource: Any =>
        val query = resource.getEntityManager().createQuery(sparql).setParameter("this", resource)
        query.evaluate().toList().map(_.toString())
        
      case _ => List("")
    }
  }
}