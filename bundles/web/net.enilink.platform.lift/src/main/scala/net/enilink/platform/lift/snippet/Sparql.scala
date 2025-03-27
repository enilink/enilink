package net.enilink.platform.lift.snippet

import net.enilink.komma.core.{IValue, _}
import net.enilink.komma.model.IModelAware
import net.enilink.platform.lift.rdfa.template.RDFaTemplates
import net.enilink.platform.lift.util.{CurrentContext, Globals, RdfContext, TemplateHelpers}
import net.liftweb.builtin.snippet.Embed
import net.liftweb.common.{Box, Empty, Full}
import net.liftweb.http.{PageName, RequestVar, S}
import net.liftweb.util.ClearClearable
import net.liftweb.util.Helpers._

import scala.jdk.CollectionConverters._
import scala.util.control.Exception._
import scala.xml.NodeSeq.seqToNodeSeq
import scala.xml.{Elem, NodeSeq, Null, UnprefixedAttribute}

/**
 * Global SPARQL parameters that can be shared between different snippets.
 */
object QueryParams extends RequestVar[Map[String, _]](Map.empty) {
  /**
   * Do not logUnreadVal since it may be the default case for this request variable.
   */
  override def logUnreadVal = false
}

object RdfHelpers {
  import scala.language.implicitConversions
  implicit def bindingsToMap(bindings: IBindings[_]): Map[String, _] = bindings.getKeys.asScala.map(k => k -> bindings.get(k)).toMap
}

trait SparqlHelper {
  def extractParams(ns: NodeSeq): Seq[String] = (ns \ "@data-params").text.split("\\s+").filterNot(_.isEmpty).map(_.stripPrefix("?")).toSeq

  def bindParams(params: Seq[String]): Map[String, Any] = convertParams(params.flatMap { name => S.param(name) map (name -> _) }.toMap)

  def convertParams(params: Map[String, _]): Map[String, Any] = {
    params flatMap {
      case (key, value: String) if value.startsWith("_:") => Some((key, new BlankNode(value)))
      case (key, value) =>
        catching(classOf[IllegalArgumentException]) opt {
          (key, String.valueOf(value) match {
            case s if s.startsWith("<") && s.endsWith(">") => URIs.createURI(s.substring(1, s.length - 1))
            case s => URIs.createURI(s)
          })
        } filter (!_._2.isRelative) match {
          case keyValue @ Some(_) => keyValue
          case None => Some(key, String.valueOf(value))
        }
    }
  }

  def withParameters[T](query: IQuery[T], params: Map[String, _]): IQuery[T] = {
    params foreach { p => query.setParameter(p._1, p._2) }
    query
  }

  def globalQueryParameters: Map[String, _] = {
    val map = (List(("currentUser", Globals.contextUser.vend)) ++
      CurrentContext.value.flatMap {
        _.subject match {
          case entity: IEntity => Full(("this", entity))
          case _ => Empty
        }
      } ++ QueryParams).toMap
    map
  }

  /**
   * Immediately captures current RDF context (context resources) and
   * returns a function that can be used to execute some code with this
   * captured context.
   */
  def captureRdfContext[S](ns: NodeSeq): (=> S) => S = {
    val isMetaQuery = (S.attr("target") openOr null) == "meta"
    val modelName: Box[String] = (ns \ "@data-model").headOption.map(_.text)
    val model = modelName.flatMap { name =>
      Globals.contextModelSet.vend.flatMap { ms =>
        // try to get the model from the model set
        try {
          Box.legacyNullTest(ms.getModel(URIs.createURI(name), false))
        } catch {
          case e: Exception => Empty
        }
      }
    }
    val (target, targetModel) = if (isMetaQuery) (Globals.contextModelSet.vend, Empty) else {
      val ctxResource = CurrentContext.value.flatMap { case RdfContext(s: IReference, _, _, _) => Full(s) case _ => Empty }
      val theModel = model or Globals.contextModel.vend
      (theModel.map(m => ctxResource.map(m.resolve) openOr m.getOntology), theModel)
    }
    target match {
      case Full(t) => f => Globals.contextModel.doWith(targetModel) { CurrentContext.withSubject(t) { f } }
      case _ => f => f
    }
  }
}

class Sparql extends SparqlHelper with RDFaTemplates {
  // trigger includeInferred either by HTTP parameter or by snippet attribute
  def includeInferred: Boolean = !S.param("inferred").exists(_ == "false") && S.attr("inferred", _ != "false", true)

  def render(ns: NodeSeq): NodeSeq = renderWithoutPrepare(prepare(ns))

  def renderWithoutPrepare(n: NodeSeq): NodeSeq = {
    // check if inferred statements should be distinguished from explicit statements
    def distinguishInferred(ns: NodeSeq): Boolean = {
      ns.foldLeft(false) { (distInf, n) => distInf || (n \ "@data-if").text == "inferred" || (n \ "@data-unless").text == "inferred" || distinguishInferred(n.child) }
    }
    val queryAsserted = includeInferred && S.attr("queryAsserted", _ != "false", true) && distinguishInferred(n)

    def renderResults = {
      def toBindings(firstBinding: String, row: Any) = row match {
        case b: IBindings[_] => b
        case other =>
          val b = new LinkedHashBindings[Any](1)
          b.put(firstBinding, other)
          b
      }
      CurrentContext.value match {
        case Full(rdfCtx) => rdfCtx.subject match {
          case entity: IEntity =>
            val em = entity.getEntityManager
            toSparql(n, em) map {
              case (n1, sparql, params) =>
                val query = withParameters(em.createQuery(sparql, includeInferred), params)
                query.bindResultType(null: String, classOf[IValue]).evaluate match {
                  case r: IGraphResult =>
                    n1 //renderGraph(new LinkedHashGraph(r.toList()))
                  case r: ITupleResult[_] =>
                    val firstBinding = r.getBindingNames.get(0)
                    val allTuples = r.iterator.asScala.map { row => (toBindings(firstBinding, row), includeInferred) }
                    var toRender = if (queryAsserted) {
                      // query explicit statements and prepend them to the results
                      withParameters(em.createQuery(sparql, false), params)
                        .bindResultType(null: String, classOf[IValue]).evaluate.asInstanceOf[ITupleResult[_]]
                        .iterator.asScala
                        .map { row => (toBindings(firstBinding, row), false) } ++ allTuples
                    } else allTuples
                    // ensure at least one template iteration with empty binding set if no results where found
                    if (!toRender.hasNext) toRender = List((new LinkedHashBindings[Object], false)).iterator
                    val transformers = (".query *" #> sparql) & ClearClearable
                    val result = renderTuples(rdfCtx, transformers(n1), toRender)
                    result
                  case _ => n1
                }
            } openOr {
              // ensure at least one template iteration with empty binding set
              renderTuples(rdfCtx, ClearClearable(n), List((new LinkedHashBindings[Object], false)).iterator)
            }
          case _ => n
        }
        case _ => n
      }
    }
    captureRdfContext(n)(renderResults)
  }

  def toSparql(n: NodeSeq, em: IEntityManager): Box[(NodeSeq, String, Map[String, _])] = n.collectFirst {
    case e: Elem if e.attribute("data-sparql").exists(_.nonEmpty) =>
      (n, e.attribute("data-sparql").map(_.text).get, globalQueryParameters ++ bindParams(extractParams(n)))
  }

  def prepare(ns: NodeSeq): NodeSeq = {
    def applyRules(ns: NodeSeq): NodeSeq = {
      import TemplateHelpers._
      ns flatMap {
        case e: Elem =>
          // process the data-embed attribute
          e.attribute("data-embed") match {
            case Some(what) =>
              S.withAttrs(S.mapToAttrs(List("what" -> what.text).toMap)) {
                val embedded = withTemplateNames(Embed.render(e.child))
                // allows to specify a template name
                (e.attribute("data-template") match {
                  case Some(tname) => find(embedded, tname.text).toSeq
                  case _ => embedded
                }) map {
                  // annotate with template path
                  case elem: xml.Elem => elem % ("data-t-path" -> what)
                  case other => other
                }
              }
            case _ => e
          }
        case other => other
      } flatMap {
        // process data-if*** and data-unless*** attributes
        case e: Elem =>
          var condTransform: Box[ConditionalTransform] = Empty
          def throwException() : Unit = { throw new IllegalArgumentException("data-if-* and data-unless-* may not be applied at the same time") }
          def setTransform(isIf: Boolean): Unit = condTransform match {
            case Empty => condTransform = Full(if (isIf) new If() else new Unless())
            case Full(_: If) if !isIf => throwException()
            case Full(_: Unless) if isIf => throwException()
            case _ =>
          }
          val tests = e.attributes.collect {
            case UnprefixedAttribute(name, value, _) if name.startsWith("data-if-") =>
              setTransform(true); (name.substring(8), value.text)
            case UnprefixedAttribute(name, value, _) if name.startsWith("data-unless-") => setTransform(false); (name.substring(12), value.text)
          }
          condTransform.map(_.evaluate(tests, e, e.attributes, Full(applyRules))) openOr e.copy(child = applyRules(e.child))
        case other => other
      }
    }
    applyRules(ns)
  }

  def renderTuples(ctx: RdfContext, ns: Seq[xml.Node], rows: Iterator[(IBindings[_], Boolean)]): NodeSeq = {
    val template = createTemplate(ns)
    rows foreach { row => template.transform(ctx, row._1, row._2) }

    val result = ClearClearable.apply(S.session.map(_.processSurroundAndInclude(PageName.get, template.render)) openOr Nil)
    result.map {
      case e: Elem =>
        // add data-model attribute
        val e1 = (e.attribute("data-model") match {
          case Some(_) => e
          case None => ctx match {
            case RdfContext(s: IModelAware, _, _, _) => e % ("data-model" -> s.getModel.getURI.toString)
            case _ => e
          }
          // add data-resoure attribute
        }) % ("data-resource", ctx.subject.toString)

        // add RDFa prefix declarations
        val xmlns = "xmlns:?([^=]+)=\"(\\S+)\"".r.findAllIn(e1.scope.buildString(scala.xml.TopScope)).matchData.map(m => (m.group(1), m.group(2))).toList
        if (xmlns.isEmpty) e1 else {
          // add RDFa 1.1 prefix attribute
          var attributes = e1.attributes.append(
            new UnprefixedAttribute("prefix", xmlns.foldLeft(new StringBuilder((e1 \ "@prefix").text)) {
              (sb, mapping) =>
                if (sb.nonEmpty) sb.append(" ")
                sb.append(mapping._1).append(": ").append(mapping._2)
            }.toString, Null))
          // add legacy xmlns attributes
          attributes = xmlns.foldLeft(attributes) { (attrs, mapping) => attrs.append(new UnprefixedAttribute("xmlns:" + mapping._1, mapping._2, attrs)) }
          e1.copy(attributes = attributes)
        }
      case other => other
    }
  }
}