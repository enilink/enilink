package net.enilink.lift.snippet

import scala.xml._
import net.enilink.komma.core.IEntityManager
import net.enilink.lift.rdfa.SparqlFromRDFa
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.http.PaginatorSnippet
import net.liftweb.builtin.snippet.Form
import net.liftweb.util.CssSel
import net.liftweb.util.PassThru
import net.liftweb.util.Helpers._
import net.liftweb.util.Helpers
import net.liftweb.util.Helpers.strToCssBindPromoter
import net.liftweb.http.Templates
import net.liftweb.common.Full
import net.liftweb.http.S
import net.enilink.komma.core.IReference
import net.enilink.komma.core.URIs
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
import net.enilink.lift.util.TemplateHelpers
import net.liftweb.http.js.JsCmds
import net.liftweb.http.js.JsCmd
import net.enilink.komma.core.QueryFragment
import net.liftweb.util.CanBind._
import net.liftweb.util.DynoVar
import scala.util.DynamicVariable
import net.enilink.komma.model.ModelUtil

object ParamsHelper {
  def params(filter: Set[String] = Set.empty) = {
    def include = S.session.map(session => {
      name: String => !(filter.contains(name) || name.matches("^F[0-9].*")) //|| session.findFunc(name).isDefined) // requires Lift 2.5
    }) openOr ((name: String) => !filter.contains(name))
    S.request.map(_._params.collect { case (name, value :: Nil) if include(name) => (name, value) }) openOr Map.empty
  }
}

/**
 * Support full-text search for RDFa templates.
 */
object Search extends SparqlHelper with SparqlExtractor {
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
      val splitRegex = "[^\\p{L}\\d_]+"
      (v match {
        case ref: IReference => (if (ref.getURI != null) ref.getURI.segments.toList ++ List(ref.getURI.localPart) else Nil) ++ List(ModelUtil.getLabel(ref))
        case literal if literal != null => List(literal.toString)
        case _ => Nil
      }).flatMap(_.split(splitRegex)).filter(_.toLowerCase.contains(query))
    }
    val origParams = QueryParams.get
    val runWithContext: (=> Any) => Any = captureRdfContext
    S.fmapFunc(S.contextFuncBuilder(SFuncHolder({ query: String =>
      runWithContext {
        lazy val default = JsonResponse(JArray(List(JString(query))))
        CurrentContext.value match {
          case Full(rdfCtx) => rdfCtx.subject match {
            case entity: IEntity => {
              println("Searching in " + entity)
              val em = entity.getEntityManager
              val keywords = bindingNames.flatMap { bindingName =>
                // add search patterns to template
                val fragment = em.getFactory.getDialect.fullTextSearch(List(bindingName), IDialect.DEFAULT, query)
                val nsWithPatterns = (".search-patterns" #> <div data-pattern={ fragment.toString } class="clearable"></div>).apply(ns)
                val sparqlFromRdfa = extractSparql(nsWithPatterns)
                val queryParams = origParams ++ globalQueryParameters ++ bindParams(extractParams(ns)) ++ bindingsToMap(fragment.bindings)
                val sparql = sparqlFromRdfa.getQuery(bindingName, 0, 1000)
                val results = withParameters(em.createQuery(sparql), queryParams).evaluate
                results.iterator.flatMap(toTokens(query.toLowerCase, _))
              }
              JsonResponse(JArray(JString(query) :: keywords.toSet[String].map(JString(_)).toList))
            }
            case _ => default
          }
          case _ => default
        }
      }
    })))({ name =>
      (name,
        JsCmds.Function(name, List("query", "process"),
          JsCmds.Run(SHtml.makeAjaxCall(JsRaw("'" + name + "=' + encodeURIComponent(query)"),
            AjaxContext.json(Full("function(result) { process(result); }"))).toJsCmd + ";")))
    });
  }

  def apply(ns: NodeSeq, render: NodeSeq => NodeSeq): NodeSeq = {
    var bindingNames: List[String] = Nil
    var searchString: Box[String] = Empty
    var param: String = null
    var fragment: Box[QueryFragment] = Empty

    def patterns(ns: NodeSeq) = {
      bindingNames = (ns \ "@data-for").text.split("\\s+").filter(_.nonEmpty).map(_.stripPrefix("?")).toList
      if (bindingNames.nonEmpty) {
        param = (ns \ "@data-param").text
        searchString = Full(ns \ "@data-value" text).filter(_.nonEmpty) or S.param(param).filter(_.nonEmpty)
        searchString.dmap(Nil: NodeSeq) { searchStr =>
          (Globals.contextModel.vend.map(_.getManager) or Globals.contextModelSet.vend.map(_.getMetaDataManager)) map { em =>
            fragment = Full(em.getFactory.getDialect.fullTextSearch(bindingNames, IDialect.DEFAULT, searchStr))
            <div data-pattern={ fragment.dmap("")(_.toString) } class="clearable"></div>
          } openOr Nil
        }
      } else ns
    }

    var nodes = (".search-patterns" #> patterns _: CssSel)(ns)
    nodes = (".search-form" #> ((form: NodeSeq) => {
      val (acName, acCmd) = autoCompleteJs(bindingNames, ns)
      JsCmds.Script(acCmd) ++ (Templates(List("templates-hidden", "search")) map {
        var queryParams = ParamsHelper.params(Set(param)).toList
        (".search-query" #> ("* [name]" #> param & "* [value]" #> searchString & "* [data-source]" #> acName)) andThen
          "form" #> {
            // support refresh via ajax call
            "*" #> (RdfaRefreshFunc.value match {
              case Full(name) =>
                // add refresh function to query parameters
                queryParams = (name, name) :: queryParams
                // use an ajax form
                ns: NodeSeq => Form.render(ns) flatMap { ("form [class]" #> (ns \ "@class").text).apply(_) }
                case _ => PassThru
            }) &
              "* *" #> ((ns: NodeSeq) => ns ++ (queryParams map {
                case (key, value) => <input type="hidden" name={ key } value={ value }></input>
              }))
          }
      } openOr Nil)
    })).apply(nodes)
    fragment map { f => QueryParams.doWith(QueryParams.get ++ bindingsToMap(f.bindings))(render(nodes)) } openOr render(nodes)
  }
}

/**
 * Converts RDFa templates to SPARQL queries.
 */
trait SparqlExtractor {
  def extractSparql(n: NodeSeq): SparqlFromRDFa = {
    val nodesWithAcl = (".acl" #> Acl.render _).apply(n)
    SparqlFromRDFa(nodesWithAcl.head.asInstanceOf[Elem], S.request.map(r => r.hostAndPath + r.uri + "#") openOr "urn:")
  }
}

private object RdfaRefreshFunc extends DynamicVariable[Box[String]](Empty)

class Rdfa extends Sparql with SparqlExtractor {
  /**
   * Support partial refresh via ajax call
   */
  def makeAjaxRefresh(ns: NodeSeq) = {
    val origParams = QueryParams.get
    val origAttrs = S.attrsToMetaData
    val refresh = (funcId: String) => {
      val result = S.withAttrs(origAttrs) {
        // run rdfa snippet while reusing current refresh function
        RdfaRefreshFunc.withValue(Full(funcId)) { QueryParams.doWith(origParams ++ QueryParams.get) { new Rdfa().render(ns) } }
      }
      JsCmds.SetHtml(funcId, result)
    }
    // create refresh function and return the corresponding id
    S.fmapFunc(S.contextFuncBuilder(refresh))(name => name)
  }

  def callAjaxRefresh(name: String, params: List[(String, String)]) = {
    SHtml.makeAjaxCall(Str(name + "=" + name + "&" + paramsToUrlParams(params))).cmd
  }

  override def render(ns: NodeSeq): NodeSeq = {
    logTime("RDFa template") {
      // initialize ajax refresh function
      val refreshFunc = RdfaRefreshFunc.value or (S.attr("mode").filter(_ == "ajax") map { _ => makeAjaxRefresh(ns) })
      RdfaRefreshFunc.withValue(refreshFunc) {
        // add node id for SetHTML via ajax refresh
        val nodesWithId = RdfaRefreshFunc.value match {
          case Full(name) => ns flatMap {
            case e: Elem => e % ("id" -> name)
            case other => other
          }
          case _ => ns
        }
        val transformers = prepare _ andThen TemplateHelpers.withTemplateNames _
        Search(transformers(nodesWithId), super.renderWithoutPrepare _)
      }
    }
  }

  def paginate(sparqlFromRdfa: SparqlFromRDFa, queryParams: Map[String, _], em: IEntityManager) = {
    // support for pagination of results
    var paginatedQuery: Box[String] = Empty
    var countQuery: String = null
    var nodesWithPagination = (".pagination" #> ((ns: NodeSeq) => {
      var bindingName = (ns \ "@data-for").text.stripPrefix("?")
      if (!bindingName.isEmpty) {
        countQuery = sparqlFromRdfa.getCountQuery(bindingName)
        def paramsForOffset(offsetParam: String, offset: Long): List[(String, String)] = {
          (ParamsHelper.params(Set(offsetParam)) ++ List(offsetParam -> offset.toString)) toList
        }
        val paginator = new PaginatorSnippet[AnyRef] {
          // ensure that offset param does not interfere with non-ajax offset
          override def offsetParam = RdfaRefreshFunc.value match { case Full(name) => "offset_" + name case _ => "offset" }

          override def pageUrl(offset: Long): String = {
            appendParams(S.uri, paramsForOffset(offsetParam, offset))
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
              <li>{
                RdfaRefreshFunc.value match {
                  case Full(name) => <a href="javascript://" onclick={
                    callAjaxRefresh(name, paramsForOffset(offsetParam, newFirst)).toJsCmd
                  }>{ ns }</a>
                  case _ => <a href={ pageUrl(newFirst) }>{ ns }</a>
                }
              }</li>

          override def itemsPerPage = try { (ns \ "@data-items").text.toInt } catch { case _: Throwable => 20 }
          lazy val cachedCount = withParameters(em.createQuery(countQuery, includeInferred), queryParams).getSingleResult(classOf[Long])
          def count = cachedCount
          def page = Nil

          // select last page if count < offset
          override def first = super.first min (count / itemsPerPage * itemsPerPage)

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
          paginator.paginate(ns.asInstanceOf[Elem].copy(child = Templates("templates-hidden" :: "pagination" :: Nil).openOrThrowException("Pagination template not found.")))
        } else paginator.paginate(ns)
      } else ns
    })).apply(sparqlFromRdfa.getElement)

    paginatedQuery match {
      case Full(q) => ((".count-query *" #> countQuery).apply(nodesWithPagination), q, queryParams)
      case _ => (sparqlFromRdfa.getElement, sparqlFromRdfa.getQuery, queryParams)
    }
  }

  override def toSparql(n: NodeSeq, em: IEntityManager): (NodeSeq, String, Map[String, _]) = {
    val sparqlFromRdfa = extractSparql(n)
    val queryParams = globalQueryParameters ++ bindParams(extractParams(n))
    paginate(sparqlFromRdfa, queryParams, em)
  }
}