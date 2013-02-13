package net.enilink.lift.snippet

import scala.collection.JavaConversions.asScalaIterator
import scala.collection._
import scala.util.control.Exception._
import scala.xml.Elem
import scala.xml.NodeSeq
import scala.xml.NodeSeq.seqToNodeSeq
import scala.xml.UnprefixedAttribute
import net.enilink.komma.core._
import net.enilink.core.ModelSetManager
import net.enilink.lift.rdfa.template.RDFaTemplates
import net.enilink.lift.util.Globals
import net.liftweb.common.Box.box2Option
import net.liftweb.common.Full
import net.liftweb.http.PageName
import net.liftweb.http.S
import net.liftweb.util.ClearClearable
import net.enilink.komma.core.IValue
import net.liftweb.http.PaginatorSnippet
import scala.xml.Null
import net.liftweb.http.RequestVar

/**
 * Global SPARQL parameters that can be shared between different snippets.
 */
object QueryParams extends RequestVar[immutable.Map[String, _]](Map.empty)

object RdfHelpers {
  implicit def bindingsToMap(bindings: IBindings[_]): immutable.Map[String, _] = bindings.getKeys.iterator.map(k => k -> bindings.get(k)).toMap
}

trait SparqlHelper {
  val selection = Globals.contextResource.vend.openOr(null)
  val isMetaQuery = (S.attr("target") openOr null) == "meta"

  def extractBindParams(ns: NodeSeq) = (ns \ "@data-bind").text.split("\\s+").filterNot(_.isEmpty).map(_.stripPrefix("?")).toSeq

  def bindParams(params: Seq[String]) = {
    params flatMap { name =>
      S.param(name) flatMap {
        // TODO allow to bind literal values
        value => catching(classOf[IllegalArgumentException]) opt { (name, URIImpl.createURI(value)) }
      }
    } toMap
  }

  def withParameters[T](query: IQuery[T], params: Map[String, _]) = {
    CurrentContext.value.get.subject match {
      case entity: IEntity => query.setParameter("this", entity)
      case _ => // do nothing
    }
    query.setParameter("currentUser", Globals.contextUser.vend)
    QueryParams foreach { p => query.setParameter(p._1, p._2) }
    params foreach { p => query.setParameter(p._1, p._2) }
    query
  }

  def withRdfContext[S](f: => S): S = {
    CurrentContext.value match {
      case Full(_) if !isMetaQuery => f
      case _ =>
        val target = if (isMetaQuery) ModelSetManager.INSTANCE.getModelSet else selection
        target match {
          case resource: Any => CurrentContext.withValue(Full(new RdfContext(resource, null))) { f }
          case _ => f
        }
    }
  }
}

class Sparql extends SparqlHelper with RDFaTemplates {
  def includeInferred = S.attr("inferred", _ != "false", true)

  def render(n: NodeSeq): NodeSeq = {
    // check if inferred statements should be distinguished from explicit statements
    def distinguishInferred(ns: NodeSeq): Boolean = {
      ns.foldLeft(false) { (distInf, n) => distInf | (n \ "@data-if").text == "inferred" | distinguishInferred(n.child) }
    }
    val distInferred = includeInferred && distinguishInferred(n)

    def renderResults = {
      def toBindings(firstBinding: String, row: Any) = {
        row match {
          case b: IBindings[_] => b
          case other => {
            val b = new LinkedHashBindings[Any](1);
            b.put(firstBinding, other);
            b
          }
        }
      }
      CurrentContext.value match {
        case Full(rdfCtx) => rdfCtx.subject match {
          case entity: IEntity =>
            val (n1, sparql, params) = toSparql(n, entity.getEntityManager)
            val query = withParameters(entity.getEntityManager.createQuery(sparql), params)
            query.bindResultType(null: String, classOf[IValue]).evaluate match {
              case r: IGraphResult =>
                n1 //renderGraph(new LinkedHashGraph(r.toList()))
              case r: ITupleResult[_] =>
                val firstBinding = r.getBindingNames.get(0)
                val allTuples = r.map { row => (toBindings(firstBinding, row), includeInferred) }
                val toRender = (if (distInferred) {
                  // query explicit statements and prepend them to the results
                  withParameters(entity.getEntityManager.createQuery(sparql, false), params)
                    .bindResultType(null: String, classOf[IValue]).evaluate.asInstanceOf[ITupleResult[_]]
                    .map { row => (toBindings(firstBinding, row), false) } ++ allTuples
                } else allTuples)
                val result = renderTuples(n1, toRender)
                result
              case _ => n1
            }
          case _ => n
        }
        case _ => n
      }
    }
    withRdfContext(renderResults)
  }

  def toSparql(n: NodeSeq, em: IEntityManager): (NodeSeq, String, Map[String, _]) = {
    (n, n.head.child.foldLeft("")((q, c) => c match { case scala.xml.Text(t) => q + t case _ => q }), bindParams(extractBindParams(n)))
  }

  def renderTuples(template: Seq[xml.Node], r: Iterator[(IBindings[_], Boolean)]) = {
    val existing = new mutable.HashMap[Key, Seq[xml.Node]]
    var result = {
      // ensure one iteration with empty bindings set
      if (r.hasNext) r else List((new LinkedHashBindings[AnyRef], false))
    }.foldLeft(Nil: NodeSeq)((transformed, row) => transform(CurrentContext.value.get, template)(row._1, row._2, existing))
    result = ClearClearable.apply(S.session.get.processSurroundAndInclude(PageName.get, processSurroundAndInclude(result)))
    result.map(_ match {
      case e: Elem => {
        // add RDFa prefix declarations
        val xmlns = "xmlns:?([^=]+)=\"(\\S+)\"".r.findAllIn(e.scope.buildString(scala.xml.TopScope)).matchData.map(m => (m.group(1), m.group(2))).toList
        if (xmlns.isEmpty) e else {
          // add RDFa 1.1 prefix attribute
          var attributes = e.attributes.append(
            new UnprefixedAttribute("prefix", xmlns.foldLeft(new StringBuilder((e \ "@prefix").text)) {
              (sb, mapping) =>
                if (sb.length > 0) sb.append(" ")
                sb.append(mapping._1).append(": ").append(mapping._2)
            }.toString, Null))
          // add legacy xmlns attributes
          attributes = xmlns.foldLeft(attributes) { (attrs, mapping) => attrs.append(new UnprefixedAttribute("xmlns:" + mapping._1, mapping._2, attrs)) }
          e.copy(attributes = attributes)
        }
      }
      case other => other
    })
  }
}