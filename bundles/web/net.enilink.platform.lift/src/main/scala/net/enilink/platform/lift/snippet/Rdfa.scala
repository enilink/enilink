package net.enilink.platform.lift.snippet

import net.enilink.komma.core._
import net.enilink.komma.model.ModelUtil
import net.enilink.platform.lift.rdfa.SparqlFromRDFa
import net.enilink.platform.lift.util.{CurrentContext, Globals, TemplateHelpers}
import net.liftweb.builtin.snippet.Form
import net.liftweb.common.{Box, Empty, Full}
import net.liftweb.http.S.SFuncHolder
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds
import net.liftweb.http.{AjaxContext, JsonResponse, PaginatorSnippet, S, SHtml, Templates}
import net.liftweb.json._
import net.liftweb.util.CanBind.StringToCssBindPromoter
import net.liftweb.util.Helpers._
import net.liftweb.util.{CssSel, PassThru}

import scala.jdk.CollectionConverters._
import scala.util.DynamicVariable
import scala.xml._

/**
 * Helper object for query parameters.
 */
object ParamsHelper {
  /**
   * Returns a map of current query parameters excluding the parameters contained in `filter`.
   */
  def params(filter: Set[String] = Set.empty): Map[String, String] = {
    def include = S.session.map(session => {
      name: String => !(filter.contains(name) || name.matches("^F[0-9].*")) //|| session.findFunc(name).isDefined) // requires Lift 2.5
    }) openOr ((name: String) => !filter.contains(name))
    // TODO: check best practices wrt. HPP (HTTP Parameter Pollution) ie. what to do about multiple values for a single parameter
    // currently, the first entry of the list is used, the others are discarded
    S.request.map(_.params.collect { case (name, value :: _) if include(name) => (name, value) }) openOr Map.empty
  }
}

/**
 * Support full-text search for RDFa templates.
 */
object Search extends SparqlHelper with SparqlExtractor {
  import RdfHelpers._

  val ESCAPE_CHARS = java.util.regex.Pattern.compile("[\\[.{(*+?^$|]")
  def patternToRegex(pattern: String, flags: Int = 0) = {
    ESCAPE_CHARS.matcher(pattern).replaceAll("\\\\$0").replace("\\*", ".*").replace("\\?", ".")
  }

  /**
   * Generates an Ajax function for auto-completion that can be executed by using a named Javascript function.
   */
  def autoCompleteJs(bindingNames: Seq[String], ns: NodeSeq) = {
    /**
     * Converts search results to candidate tokens for auto-completion.
     *
     * TODO Maybe this should directly be integrated into the SPARQL query to get all results?!
     */
    def toTokens(v: Any): Seq[String] = {
      val splitRegex = "[^\\p{L}\\d_]+"
      (v match {
        case ref: IReference => (if (ref.getURI != null) ref.getURI.segments.toList ++ List(ref.getURI.localPart) else Nil) ++ List(ModelUtil.getLabel(ref))
        case literal if literal != null => List(literal.toString)
        case _ => Nil
      }).flatMap(_.split(splitRegex))
    }
    val origParams = QueryParams.get
    val runWithContext: (=> Any) => Any = captureRdfContext(ns)
    S.fmapFunc(S.contextFuncBuilder(SFuncHolder({ query: String =>
      runWithContext {
        lazy val default = JsonResponse(JArray(List(JString(query))))
        CurrentContext.value match {
          case Full(rdfCtx) => rdfCtx.subject match {
            case entity: IEntity => {
              val em = entity.getEntityManager
              val keywords = bindingNames.flatMap { bindingName =>
                // add search patterns to template
                val fragment = em.getFactory.getDialect.fullTextSearch(List(bindingName).asJava, IDialect.DEFAULT, query)
                val nsWithPatterns = (".search-patterns" #> <div data-pattern={ fragment.toString } class="clearable"></div>).apply(ns)
                val sparqlFromRdfa = extractSparql(nsWithPatterns)
                val queryParams = origParams ++ globalQueryParameters ++ bindParams(extractParams(ns)) ++ bindingsToMap(fragment.bindings)
                val sparql = sparqlFromRdfa.getQuery(bindingName, 0, 1000)
                val results = withParameters(em.createQuery(sparql), queryParams).evaluateRestricted(classOf[IValue])
                val queryRegex = patternToRegex(query.toLowerCase).r
                results.iterator.asScala.flatMap(toTokens(_).filter(token => queryRegex.findFirstIn(token.toLowerCase).isDefined)) ++ toTokens(query.toLowerCase)
              }
              JsonResponse(JArray(keywords.toSet[String].toList.sorted.map(JString(_))))
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
        searchString = Full((ns \ "@data-value").text).filter(_.nonEmpty) or S.param(param).filter(_.nonEmpty)
        searchString.dmap(Nil: NodeSeq) { searchStr =>
          (Globals.contextModel.vend.map(_.getManager) or Globals.contextModelSet.vend.map(_.getMetaDataManager)) map { em =>
            fragment = Full(em.getFactory.getDialect.fullTextSearch(bindingNames.asJava, IDialect.DEFAULT, searchStr))
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
    SparqlFromRDFa(nodesWithAcl.headOption.map(_.asInstanceOf[Elem]) getOrElse <div></div>, S.request.map(r => r.hostAndPath + r.uri) openOr "http://unknown/")
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
    val origCtx = CurrentContext.value
    val refresh = (funcId: String) => {
      val result = S.withAttrs(origAttrs) {
        // run rdfa snippet while reusing current refresh function
        RdfaRefreshFunc.withValue(Full(funcId)) { CurrentContext.withValue(origCtx) {
          QueryParams.doWith(origParams ++ QueryParams.get) { new Rdfa().render(ns) } }
        }
      }
      JsCmds.SetHtml(funcId, result) & JsCmds.Run(s"$$('#$funcId').trigger('rdfa-refresh')")
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
    var nodesWithPagination = (".paginator" #> ((ns: NodeSeq) => {
      var bindingName = (ns \ "@data-for").text.stripPrefix("?")
      if (!bindingName.isEmpty) {
        countQuery = sparqlFromRdfa.getCountQuery(bindingName)
        def paramsForOffset(offsetParam: String, offset: Long): List[(String, String)] = {
          (ParamsHelper.params(Set(offsetParam)) + (offsetParam -> offset.toString)).toList
        }
        val paginator = new PaginatorSnippet[AnyRef] {
          // ensure that offset param does not interfere with non-ajax offset
          override def offsetParam = RdfaRefreshFunc.value match { case Full(name) => "offset_" + name case _ => "offset" }

          override def pageUrl(offset: Long): String = {
            appendParams(S.uri, paramsForOffset(offsetParam, offset))
          }

          val Nr = "([0-9]+)".r
          override def pageXml(newFirst: Long, ns: NodeSeq): NodeSeq = {
            if (first == newFirst || newFirst < 0 || newFirst >= count)
              <li class={
                ns match {
                  case Text(Nr(_)) => "active"
                  case _ => "disabled"
                }
              }><a href="javascript:void(0)">{ ns }</a></li>
            else
              <li>{
                RdfaRefreshFunc.value match {
                  case Full(name) => <a href="javascript://" onclick={
                    callAjaxRefresh(name, paramsForOffset(offsetParam, newFirst)).toJsCmd
                  }>{ ns }</a>
                  case _ => <a href={ pageUrl(newFirst) }>{ ns }</a>
                }
              }</li>
          }

          // replaces the annotated elements instead of using them as a container
          override def paginate: CssSel = {
            import scala.math._

            ".first" #> pageXml(0, firstXml) &
              ".prev" #> pageXml(max(first - itemsPerPage, 0), prevXml) &
              ".all-pages" #> pagesXml(0 until numPages) _ &
              ".zoomed-pages" #> pagesXml(zoomedPages) _ &
              ".next" #> pageXml(
                max(0, min(first + itemsPerPage, itemsPerPage * (numPages - 1))),
                nextXml
              ) &
              ".last" #> pageXml(itemsPerPage * (numPages - 1), lastXml) &
              ".records" #> currentXml &
              ".records-start" #> recordsFrom &
              ".records-end" #> recordsTo &
              ".records-count" #> count
          }

          override def itemsPerPage = try { (ns \ "@data-items").text.toInt } catch { case _: Throwable => 20 }
          lazy val cachedCount = withParameters(em.createQuery(countQuery, includeInferred), queryParams).getSingleResult(classOf[Long])
          def count = cachedCount
          def page = Nil

          // select last page if count < offset
          override def first = super.first min (count - ((count % itemsPerPage) match { case 0 => itemsPerPage case r => r })) max 0
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

  override def toSparql(n: NodeSeq, em: IEntityManager): Box[(NodeSeq, String, Map[String, _])] = {
    val sparqlFromRdfa = extractSparql(n)
    if (sparqlFromRdfa.getQueryVariables.isEmpty) Empty else {
      val queryParams = globalQueryParameters ++ bindParams(extractParams(n))
      Full(paginate(sparqlFromRdfa, queryParams, em))
    }
  }
}