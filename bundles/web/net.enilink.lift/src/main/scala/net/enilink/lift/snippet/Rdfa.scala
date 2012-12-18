package net.enilink.lift.snippet

import scala.xml.Elem
import scala.xml.NodeSeq
import scala.xml.Text
import scala.xml.Text
import net.enilink.komma.core.IEntityManager
import net.enilink.lift.rdfa.SparqlFromRDFa
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.http.PaginatorSnippet
import net.liftweb.util.CssSel
import net.liftweb.util.Helpers._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers.strToCssBindPromoter
import net.liftweb.http.Templates
import net.liftweb.common.Full
import net.liftweb.http.S
import net.enilink.lift.rdf.DollarVariable
import net.enilink.komma.core.IReference
import net.enilink.komma.core.URIImpl
import scala.util.control.Exception._

/**
 * Required to call super.render in EditRdfa trait
 */
trait HasRender {
  def render(n: NodeSeq): NodeSeq
}

trait EditRdfa extends HasRender {
  abstract override def render(n: NodeSeq): NodeSeq = {
    var tid = 0
    def withTemplateIds(n: NodeSeq): NodeSeq = n.flatMap {
      _ match {
        case e: Elem =>
          val newE = e.attribute("data-tid") match {
            case Some(id) => e
            case None => e % ("data-tid" -> { tid = tid + 1; tid })
          }
          if (newE.child.isEmpty) newE else newE.copy(child = withTemplateIds(newE.child))
        case other => other
      }
    }
    super.render(withTemplateIds(n))
  }
}

class Rdfa extends Sparql with EditRdfa {
  override def render(n: NodeSeq): NodeSeq = {
    super.render(n)
  }

  override def toSparql(n: NodeSeq, em: IEntityManager): (NodeSeq, String, Map[String, Object]) = {
    val nodesWithAcl = (".acl" #> Acl.render _)(n)
    val sparqlFromRdfa = SparqlFromRDFa(nodesWithAcl.head.asInstanceOf[Elem], "http://example.org#")

    val queryParams = sparqlFromRdfa.getQueryVariables.flatMap {
      case v: DollarVariable =>
        val name = v.toString
        S.param(name) flatMap { value => catching(classOf[IllegalArgumentException]) opt { (name, URIImpl.createURI(value)) } }
      case _ => Empty
    }.toMap

    // support for pagination of results
    var paginatedQuery: Box[String] = Empty
    var nodesWithPagination = (".pagination" #> ((ns: NodeSeq) => {
      var bindingName = (ns \ "@data-for").text.stripPrefix("?").stripPrefix("$")
      if (!bindingName.isEmpty) {
        val paginator = new PaginatorSnippet[AnyRef] {
          override def pageUrl(offset: Long): String = {
            val params = S.request.map(_._params.collect { case (name, value :: Nil) if name != offsetParam => (name, value) }) openOr Map.empty
            appendParams(S.uri, params.toList ++ List(offsetParam -> offset.toString))
          }

          val Nr = "([0-9]+)".r
          override def pageXml(newFirst: Long, ns: NodeSeq): NodeSeq =
            if (first == newFirst || newFirst < 0 || newFirst >= count)
              <li class={
                ns match {
                  case Text(Nr(_)) => "active"
                  case _ => "disabled"
                }
              }><a href="#">{ ns }</a></li>
            else
              <li><a href={ pageUrl(newFirst) }>{ ns }</a></li>

          override def itemsPerPage = try { (ns \ "@data-items").text.toInt } catch { case _ => 20 }
          lazy val cachedCount = withParameters(em.createQuery(sparqlFromRdfa.getCountQuery(bindingName), includeInferred), queryParams).getSingleResult(classOf[Long])
          def count = cachedCount
          def page = Nil

          override def paginate(ns: NodeSeq) = {
            // support lower case tags
            bind(navPrefix, super.paginate(ns),
              "recordsfrom" -> Text(recordsFrom),
              "recordsto" -> Text(recordsTo),
              "recordscount" -> Text(count.toString))
          }
        }
        paginatedQuery = Full(sparqlFromRdfa.getPaginatedQuery(bindingName, paginator.first, paginator.itemsPerPage))

        if (ns.head.child.forall(_.isInstanceOf[Text])) {
          // use default template for pagination controls
          paginator.paginate(ns.asInstanceOf[Elem].copy(child = Templates("templates-hidden" :: "pagination" :: Nil).openTheBox))
        } else paginator.paginate(ns)
      } else ns
    }))(sparqlFromRdfa.getElement)

    if (paginatedQuery.isDefined) (nodesWithPagination, paginatedQuery.openTheBox, queryParams)
    else (sparqlFromRdfa.getElement, sparqlFromRdfa.getQuery, queryParams)
  }
}