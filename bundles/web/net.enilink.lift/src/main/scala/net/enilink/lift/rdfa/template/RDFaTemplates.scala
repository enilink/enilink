package net.enilink.lift.rdfa.template

import scala.collection.mutable
import scala.xml.Elem
import scala.xml.MetaData
import scala.xml.NodeSeq
import scala.xml.UnprefixedAttribute
import net.enilink.komma.core.IBindings
import net.enilink.komma.core.IReference
import net.enilink.lift.snippet.CurrentContext
import net.enilink.lift.snippet.RdfContext
import net.liftweb.common.Full
import net.liftweb.http.PageName
import net.liftweb.http.S
import net.liftweb.util.Helpers.pairToUnprefixed
import net.liftweb.util.Helpers.strToSuperArrowAssoc
import net.enilink.lift.rdfa.RDFaUtils
import net.enilink.komma.core.ILiteral
import scala.xml.Null
import net.enilink.lift.snippet.RdfContext
import net.enilink.komma.core.URI
import net.enilink.komma.core.IEntity
import net.enilink.komma.core.URIImpl
import scala.xml.NamespaceBinding
import scala.collection.mutable.LinkedHashMap
import scala.xml.Node
import net.enilink.lift.rdfa.RDFaUtils

trait Binder {
  type Result = (MetaData, RdfContext, Boolean)
  final val Attribute = "^(?:data-(?:clear-)?)?(.+)".r

  def shorten(uri: URI, currentCtx: RdfContext, elem: xml.Elem) = {
    val namespace = uri.namespace.toString
    lazy val uriStr = uri.toString
    var prefix = currentCtx.prefix.getPrefix(namespace)
    if (prefix == null) prefix = elem.scope.getPrefix(namespace)
    if (prefix == null) uri.toString else prefix + ":" + uriStr.substring(scala.math.min(namespace.length, uriStr.length))
  }

  def shortRef(ctx: RdfContext, elem: xml.Elem, attr: String, ref: IReference) = {
    val uri = ref.getURI
    if (uri == null) ref
    else if (uri.localPart.isEmpty || (attr match { case Attribute("href" | "src") => true case _ => false })) uri
    else shorten(uri, ctx, elem)
  }

  def changeContext(ctx: RdfContext, attributeName: String, rdfValue: Any) = attributeName match {
    case Attribute("rel" | "rev" | "property") =>
      ctx.copy(predicate = rdfValue)
    case Attribute("about" | "src" | "href" | "resource" | "content") =>
      ctx.copy(subject = rdfValue, predicate = null)
    case _ => ctx
  }

  def priority: Int = 0

  def bind(attrs: MetaData, ctx: RdfContext, bindings: IBindings[_], inferred: Boolean): Result
}

class IfInferredBinder(val key: String) extends Binder {
  def bind(attrs: MetaData, ctx: RdfContext, bindings: IBindings[_], inferred: Boolean): Result = (attrs.remove(key), ctx, !inferred)
}

class UnlessInferredBinder(val key: String) extends Binder {
  def bind(attrs: MetaData, ctx: RdfContext, bindings: IBindings[_], inferred: Boolean): Result = (attrs.remove(key), ctx, inferred)
}

trait RdfAttributeBinder extends Binder {
  val attr: String

  /**
   * Correctly change RDF context by executing bindings in correct order.
   */
  override def priority = attr match {
    case Attribute("rel" | "rev" | "property") => 5
    case Attribute("resource" | "content") => 10
    case _ => 0
  }
}

class VarBinder(val e: Elem, val attr: String, val name: String) extends RdfAttributeBinder {
  def bind(attrs: MetaData, ctx: RdfContext, bindings: IBindings[_], inferred: Boolean): Result = {
    var attributes = attrs
    val rdfValue = bindings.get(name)
    var currentCtx = ctx
    if (rdfValue != null) currentCtx = changeContext(currentCtx, attr, rdfValue)
    val attValue = rdfValue match {
      case ref: IReference => shortRef(ctx, e, attr, ref)
      case literal: ILiteral => {
        // add datatype and lang attributes
        if (literal.getDatatype != null) {
          attributes = attributes.append(new UnprefixedAttribute("datatype", shorten(literal.getDatatype, currentCtx, e), Null))
        } else if (literal.getLanguage != null) {
          attributes = attributes.append(new UnprefixedAttribute("lang", literal.getLanguage, Null))
        }
        literal.getLabel
      }
      case other => other
    }

    if (attValue == null) {
      if (attr == "data-unless") attributes = attributes.remove(attr) else attributes = null
    } else if (attr.startsWith("data-clear-") || attr == "data-if") attributes = attributes.remove(attr)
    else if (attr == "data-unless") { attributes = null }
    else attributes = attributes.append(new UnprefixedAttribute(attr, attValue.toString, Null))
    (attributes, currentCtx, false)
  }
}

class IriBinder(val e: Elem, val attr: String, val Iri: URI) extends RdfAttributeBinder {
  def bind(attrs: MetaData, ctx: RdfContext, bindings: IBindings[_], inferred: Boolean): Result = {
    // do also switch contexts for given constant CURIEs
    val rdfValue = ctx.subject match {
      case e: IEntity => e.getEntityManager.find(Iri)
      case _ => null
    }
    if (rdfValue != null) {
      (attrs.append(new UnprefixedAttribute(attr, String.valueOf(shortRef(ctx, e, attr, rdfValue)), Null)), changeContext(ctx, attr, rdfValue), false)
    } else (attrs, ctx, false)
  }

  /**
   * Correctly change RDF context by executing resource or content bindings last.
   */
  override def priority = attr match {
    case Attribute("resource" | "content") => 10
    case _ => 0
  }
}

object TemplateNode extends RDFaUtils {
  val ignoreAttributes = Set("data-search", "data-for", "data-bind")
  val variable = "^[?]([^=]+)$".r

  def unapply(n: Node): Option[(Elem, Seq[Binder])] = {
    n match {
      case e: Elem if !e.attributes.isEmpty => {
        val binders = e.attributes.flatMap { meta =>
          meta.value.text match {
            // remove nodes of type <span data-if="inferred">This data is inferred or explicit.</span>
            // if we are currently processing explicit bindings
            case "inferred" if meta.key == "data-if" => Some(new IfInferredBinder(meta.key))
            case "inferred" if meta.key == "data-unless" => Some(new UnlessInferredBinder(meta.key))
            // fill variables for nodes of type <span about="?someVar">Data about some subject.</span>
            case variable(v) => if (!ignoreAttributes.contains(meta.key)) Some(new VarBinder(e, meta.key, v)) else None
            case iriStr if !iriStr.isEmpty => {
              try {
                val Iri = URIImpl.createURI(iriStr)
                if (!Iri.isRelative()) Some(new IriBinder(e, meta.key, Iri)) else None
              } catch {
                case iae: IllegalArgumentException => None // ignore, iriStr is an invalid IRI
              }
            }
            case _ => None
          }
        }
        if (binders.isEmpty) None else Some((e, binders.toSeq.sortBy(_.priority)))
      }
      case _ => None
    }
  }
}

class TemplateNode(
  prefix: String,
  label: String,
  attributes: MetaData,
  scope: NamespaceBinding,
  val binders: Seq[Binder],
  child: xml.Node*) extends Elem(prefix, label, attributes, scope, child: _*) with RDFaUtils {
  val instances: mutable.Map[(MetaData, RdfContext), Elem] = new LinkedHashMap

  override def copy(
    prefix: String = this.prefix,
    label: String = this.label,
    attributes: MetaData = this.attributes,
    scope: NamespaceBinding = this.scope,
    child: Seq[xml.Node] = this.child.toSeq): TemplateNode = new TemplateNode(prefix, label, attributes, scope, binders, child: _*)
}

class Template(val ns: NodeSeq) {
  def deepCopy(ns: Seq[Node]): Seq[Node] = ns.map {
    case e: Elem => e.copy(child = deepCopy(e.child))
    case other => other
  }

  def transform(ctx: RdfContext, bindings: IBindings[_], inferred: Boolean) {
    def internalTransform(ctx: RdfContext, template: Seq[xml.Node]) {
      template foreach {
        case t: TemplateNode => {
          // run registered template binders
          val (rAttrs, rCtx, removeNode) = t.binders.foldLeft((t.attributes, ctx, false)) {
            case ((attrs, ctx, removeNode), binder) =>
              if (attrs != null) {
                val r = binder.bind(attrs, ctx, bindings, inferred)
                (r._1, r._2, removeNode || r._3)
              } else (attrs, ctx, removeNode)
          }
          if (rAttrs != null) {
            // the RDF context is also required as key value since
            // some attributes like "data-clear-rel" are completely removed
            // after their value was bound
            val key = (rAttrs, rCtx)
            t.instances.get(key) match {
              case Some(null) =>
              case _ if removeNode => t.instances.put(key, null)
              case Some(e: Elem) => internalTransform(rCtx, e.child)
              case None => {
                val instance = if (rCtx == ctx) {
                  new Elem(t.prefix, t.label, rAttrs, t.scope, deepCopy(t.child): _*)
                } else {
                  new ElemWithRdfa(rCtx, t.prefix, t.label, rAttrs, t.scope, deepCopy(t.child): _*)
                }
                t.instances.put(key, instance)
                internalTransform(rCtx, instance.child)
              }
            }
          }
        }
        case e: Elem => internalTransform(ctx, e.child)
        case other => // do nothing
      }
    }
    internalTransform(ctx, ns)
  }

  def render = {
    /**
     * Process lift templates while changing the RDFa context resources
     */
    def processSurroundAndInclude(ns: NodeSeq): NodeSeq = {
      def processNode(n: xml.Node): NodeSeq = {
        val value = CurrentContext.value
        n match {
          case e: ElemWithRdfa => {
            val result = e.copy(child = processSurroundAndInclude(e.child))
            CurrentContext.withValue(Full(e.context)) {
              S.session.get.processSurroundAndInclude(PageName.get, result)
            }
          }
          case e: Elem => e.copy(child = processSurroundAndInclude(e.child))
          case other => other
        }
      }
      ns.flatMap(processNode _)
    }

    def toInstances(ns: Seq[Node]): Seq[Node] = {
      ns.flatMap {
        case t: TemplateNode => t.instances.values.map {
          case e: Elem => e.copy(child = toInstances(e.child))
          case other => other
        }.filter(_ != null)
        case e: Elem => e.copy(child = toInstances(e.child))
        case other => other
      }
    }
    val instances = toInstances(ns)
    processSurroundAndInclude(instances)
  }
}

trait RDFaTemplates extends RDFaUtils {
  def createTemplateNodes(ns: NodeSeq): NodeSeq = ns.map {
    case TemplateNode(e, binders) => new TemplateNode(e.prefix, e.label, e.attributes, e.scope, binders, createTemplateNodes(e.child): _*)
    case e: Elem => e.copy(child = createTemplateNodes(e.child))
    case other => other
  }

  def createTemplate(ns: NodeSeq) = new Template(createTemplateNodes(ns))
}