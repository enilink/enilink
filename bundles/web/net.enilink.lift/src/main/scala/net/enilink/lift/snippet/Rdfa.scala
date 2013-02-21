package net.enilink.lift.snippet

import scala.xml._
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
import net.enilink.komma.core.IReference
import net.enilink.komma.core.URIImpl
import scala.util.control.Exception._
import net.liftweb.http.js.JE._
import net.liftweb.http.S.SFuncHolder
import net.enilink.lift.util.Globals
import net.enilink.core.ModelSetManager
import net.enilink.komma.core.IDialect
import net.liftweb.http.JsonResponse
import net.liftweb.http.PageName
import net.liftweb.json._
import net.liftweb.http.SHtml
import net.liftweb.http.AjaxContext
import net.enilink.komma.core.IEntity
import scala.collection.JavaConversions._
import net.enilink.komma.core.URI

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

/**
 * Support full-text search for RDFa templates.
 */
trait Search extends HasRender with SparqlHelper with SparqlExtractor {
  import RdfHelpers._

  /**
   * Generates an Ajax function for auto-completion that can be executed by using a named Javascript function.
   */
  def autoCompleteJs(bindingNames: Seq[String], ns: NodeSeq) = {
    /**
     * Converts search results to candidate tokens for auto-completion.
     *
     * TODO Maybe this should directly be integrated into the SPARQL query to get all results?!
     */
    def toTokens(query: String, v: Any): Seq[String] = {
      (v match {
        case ref: IReference if ref.getURI != null => ref.getURI.segments ++ List(ref.getURI.localPart)
        case other => other.toString.split("\\s+")
      }).filter(_.toLowerCase.contains(query))
    }

    JsRaw("function search(query, process) { " +
      (S.fmapFunc(S.contextFuncBuilder(SFuncHolder({ query: String =>
        withRdfContext({
          lazy val default = JsonResponse(JArray(List(JString(query))))
          CurrentContext.value match {
            case Full(rdfCtx) => rdfCtx.subject match {
              case entity: IEntity => {
                val em = entity.getEntityManager
                val keywords = bindingNames.flatMap { bindingName =>
                  // add search patterns to template
                  val fragment = em.getFactory.getDialect.fullTextSearch(List(bindingName), IDialect.ANY, query)
                  val nsWithPatterns = (".search-patterns" #> <div data-pattern={ fragment.toString } class="clearable"></div>)(ns)
                  val sparqlFromRdfa = extractSparql(nsWithPatterns)
                  val queryParams = bindParams(extractBindParams(ns)) ++ bindingsToMap(fragment.bindings)
                  val sparql = sparqlFromRdfa.getQuery(bindingName, 0, 100)
                  val results = withParameters(em.createQuery(sparql), queryParams).evaluate
                  results.iterator.flatMap(toTokens(query.toLowerCase, _))
                }
                JsonResponse(JArray(JString(query) :: keywords.toSet[String].map(JString(_)).toList))
              }
              case _ => default
            }
            case _ => default
          }
        })
      })))({ name =>
        SHtml.makeAjaxCall(JsRaw("'" + name + "=' + encodeURIComponent(query)"),
          AjaxContext.json(Full("function(result) { process(result); }"))).toJsCmd
      }) + "; }")).toJsCmd
  }

  abstract override def render(ns: NodeSeq): NodeSeq = {
    val bindingNames = (ns \ "@data-search").text.split("\\s+").filter(_.nonEmpty).map(_.stripPrefix("?")).toList
    if (bindingNames.nonEmpty) {
      S.putInHead(<script type="text/javascript">{ autoCompleteJs(bindingNames, ns) }</script>)
      val searchString = S.param("q").filter(_.nonEmpty)
      var transformers = ".search-form" #> Templates("templates-hidden" :: "search" :: Nil).map {
        "@q [value]" #> searchString
      }
      if (searchString.isDefined) {
        val em = Globals.contextModel.vend.dmap(ModelSetManager.INSTANCE.getModelSet.getMetaDataManager)(_.getManager)
        val fragment = em.getFactory.getDialect.fullTextSearch(bindingNames, IDialect.ANY, searchString.openTheBox)
        val results = (transformers andThen ".search-patterns" #> <div data-pattern={ fragment.toString } class="clearable"></div>)(ns)
        QueryParams.doWith(fragment.bindings)(super.render(results))
      } else super.render(transformers(ns))
    } else super.render(ns)
  }
}

/**
 * Converts RDFa templates to SPARQL queries.
 */
trait SparqlExtractor {
  def extractSparql(n: NodeSeq): SparqlFromRDFa = {
    val nodesWithAcl = (".acl" #> Acl.render _)(n)
    SparqlFromRDFa(nodesWithAcl.head.asInstanceOf[Elem], S.request.map(r => r.hostAndPath + r.uri + "#") openOr "urn:")
  }
}

class Rdfa extends Sparql with SparqlExtractor with Search with EditRdfa {
  override def render(n: NodeSeq): NodeSeq = {
    super.render(n)
  }

  def paginate(sparqlFromRdfa: SparqlFromRDFa, queryParams: Map[String, _], em: IEntityManager) = {
    // support for pagination of results
    var paginatedQuery: Box[String] = Empty
    var nodesWithPagination = (".pagination" #> ((ns: NodeSeq) => {
      var bindingName = (ns \ "@data-for").text.stripPrefix("?")
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

  override def toSparql(n: NodeSeq, em: IEntityManager): (NodeSeq, String, Map[String, _]) = {
    val sparqlFromRdfa = extractSparql(n)
    val queryParams = bindParams(extractBindParams(n))
    paginate(sparqlFromRdfa, queryParams, em)
  }
}