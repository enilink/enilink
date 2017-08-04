package net.enilink.lift.rdfa.template

import scala.collection.mutable
import scala.xml.Elem
import scala.xml.MetaData
import scala.xml.NodeSeq
import scala.xml.UnprefixedAttribute
import net.enilink.komma.core.IBindings
import net.enilink.komma.core.IReference
import net.enilink.lift.util.CurrentContext
import net.enilink.lift.util.RdfContext
import net.liftweb.common.Full
import net.liftweb.http.PageName
import net.liftweb.http.S
import net.liftweb.util.Helpers.pairToUnprefixed
import net.liftweb.util.Helpers.strToSuperArrowAssoc
import net.enilink.lift.rdfa.RDFaUtils
import net.enilink.komma.core.ILiteral
import scala.xml.Null
import net.enilink.komma.core.URI
import net.enilink.komma.core.IEntity
import net.enilink.komma.core.URIs
import scala.xml.NamespaceBinding
import scala.collection.mutable.LinkedHashMap
import scala.xml.Node
import net.enilink.lift.rdfa.RDFaUtils
import net.enilink.lift.rdfa.RDFaHelpers

import BinderHelpers._
import scala.xml.Text

sealed trait Operation {
  def merge(other: Operation): Operation = other match {
    case Keep => this
    case _ => this match {
      case Remove => Remove
      case _ => other
    }
  }
}
case object Keep extends Operation
case object RemoveInferred extends Operation
case object Remove extends Operation

object BinderHelpers {
  final val Attribute = "^(?:data-(?:clear-)?)?(.+)".r

  object RDFaRelAttribute {
    def unapply(name: String) = name match {
      case Attribute("rel" | "rev" | "property") => true
      case _ => false
    }
  }

  object RDFaResourceAttribute {
    def unapply(name: String) = name match {
      case Attribute("about" | "src" | "href" | "resource" | "content") => true
      case _ => false
    }
  }
}
trait Binder {
  type Result = (MetaData, RdfContext, Operation)

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
    case RDFaRelAttribute() =>
      ctx.childContext(predicate = rdfValue)
    case RDFaResourceAttribute() =>
      ctx.childContext(subject = rdfValue, predicate = null)
    case _ => ctx
  }

  def priority: Int = 0

  def bind(attrs: MetaData, ctx: RdfContext, bindings: IBindings[_], inferred: Boolean): Result
}

class IfInferredBinder(val key: String) extends Binder {
  def bind(attrs: MetaData, ctx: RdfContext, bindings: IBindings[_], inferred: Boolean): Result = (attrs.remove(key), ctx, if (!inferred) Remove else Keep)
}

class UnlessInferredBinder(val key: String) extends Binder {
  def bind(attrs: MetaData, ctx: RdfContext, bindings: IBindings[_], inferred: Boolean): Result = (attrs.remove(key), ctx, if (inferred) RemoveInferred else Keep)
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

class VarBinder(val e: Elem, val attr: String, val name: String, val verbatim: Boolean = false) extends RdfAttributeBinder {
  // the verbatim flag constrols if URIs should be shortened to CURIEs or not
  // allow to keep this node by adding the CSS class "keep", even if no binding exists for the related variable
  val keepNode = RDFaHelpers.hasCssClass(e, "keep")
  val clearAttribute = attr.startsWith("data-clear-") || attr == "data-if"
  def bind(attrs: MetaData, ctx: RdfContext, bindings: IBindings[_], inferred: Boolean): Result = {
    var attributes = attrs
    val rdfValue = bindings.get(name)
    var currentCtx = ctx
    if (rdfValue != null || keepNode) currentCtx = changeContext(currentCtx, attr, rdfValue)
    val attValue = rdfValue match {
      case ref: IReference => if (verbatim) ref else shortRef(ctx, e, attr, ref)
      case literal: ILiteral if !clearAttribute => {
        // add datatype and lang attributes
        if (literal.getDatatype != null) {
          attributes = attributes.append(new UnprefixedAttribute("datatype", shorten(literal.getDatatype, currentCtx, e), Null))
        }
        if (literal.getLanguage != null) {
          attributes = attributes.append(new UnprefixedAttribute("lang", literal.getLanguage, Null))
        }
        literal.getLabel
      }
      case literal: ILiteral => literal.getLabel
      case other => other
    }

    if (attValue == null) {
      if (keepNode) {
        // remove the original attribute
        attributes = attributes.remove(attr)
        // add attribute data-{attribute}-empty=""
        attributes = attributes.append(new UnprefixedAttribute("data-" + attr.stripPrefix("data-") + "-empty", "", Null))
        // add empty content and resource attribute to support editing
        attr match {
          case "content" => attributes = attributes.append(new UnprefixedAttribute(attr, "", Null))
          case "resource" => attributes = attributes.append(new UnprefixedAttribute(attr, "komma:null", Null))
        }
      } else if (attr == "data-unless") attributes = attributes.remove(attr)
      else attributes = null
    } else if (clearAttribute) attributes = attributes.remove(attr)
    else if (attr == "data-unless") { attributes = null }
    else attributes = attributes.append(new UnprefixedAttribute(attr, attValue.toString, Null))
    (attributes, currentCtx, Keep)
  }
}

class IriBinder(val e: Elem, val attr: String, val iri: URI) extends RdfAttributeBinder {
  def bind(attrs: MetaData, ctx: RdfContext, bindings: IBindings[_], inferred: Boolean): Result = {
    // do also switch contexts for given constant CURIEs
    val rdfValue = ctx.subject match {
      case e: IEntity => e.getEntityManager.find(iri)
      case _ => null
    }
    if (rdfValue != null) {
      (attrs.append(new UnprefixedAttribute(attr, String.valueOf(shortRef(ctx, e, attr, rdfValue)), Null)), changeContext(ctx, attr, rdfValue), Keep)
    } else (attrs, ctx, Keep)
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
  val ignoreAttributes = Set("data-for", "data-params", "data-bind")
  val variable = "^([?$][^= \\t]+)$".r

  def unapply(n: Node): Option[(Elem, Seq[Binder])] = {
    n match {
      case e: Elem if !e.attributes.isEmpty => {
        val binders = e.attributes.flatMap { meta =>
          meta.value.text match {
            // remove nodes of type <span data-if="inferred">This data is inferred or explicit.</span>
            // if we are currently processing explicit bindings
            case "inferred" => meta.key match {
              case "data-if" => Some(new IfInferredBinder(meta.key))
              case "data-unless" => Some(new UnlessInferredBinder(meta.key))
              case _ => None
            }
            // fill variables for nodes of type <span about="?someVar">Data about some subject.</span> or
            // <span data-var="$someVar">Verbatim replacement.</span>
            case variable(v) => if (!ignoreAttributes.contains(meta.key)) {
              val verbatim = v.startsWith("$")
              Some(new VarBinder(e, meta.key, v.substring(1), verbatim))
            } else None
            case str if !str.isEmpty => meta.key match {
              case RDFaRelAttribute() | RDFaResourceAttribute() =>
                try {
                  URIs.createURI(str) match {
                    // skip href attributes with values like "javascript:void(0)"
                    case iri if meta.key.equalsIgnoreCase("href") && iri.scheme == "javascript" => None
                    case iri if !iri.isRelative => Some(new IriBinder(e, meta.key, iri))
                    case _ => None
                  }
                } catch {
                  case iae: IllegalArgumentException => None // ignore, str is an invalid IRI
                }
              case _ => None
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
    child: xml.Node*) extends Elem(prefix, label, attributes, scope, true, child: _*) with RDFaUtils {
  val instances: mutable.Map[(MetaData, RdfContext), Elem] = new LinkedHashMap

  override def copy(
    prefix: String = this.prefix,
    label: String = this.label,
    attributes: MetaData = this.attributes,
    scope: NamespaceBinding = this.scope,
    minimizeEmpty: Boolean = this.minimizeEmpty,
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
          val (rAttrs, rCtx, op) = t.binders.foldLeft((t.attributes, ctx, Keep: Operation)) {
            case ((attrs, ctx, op), binder) =>
              if (attrs != null) {
                val r = binder.bind(attrs, ctx, bindings, inferred)
                (r._1, r._2, op.merge(r._3))
              } else (attrs, ctx, op)
          }
          if (rAttrs != null) {
            // the RDF context is also required as key value since
            // some attributes like "data-clear-rel" are completely removed
            // after their value was bound
            val key = (rAttrs, rCtx)
            t.instances.get(key) match {
              case Some(null) =>
              // remove only inferred instances and keep asserted instances
              case None if op == RemoveInferred => t.instances.put(key, null)
              // remove existing or non-existing instances
              case e if op == Remove => t.instances.put(key, null)
              case Some(e) =>
                internalTransform(rCtx, e.child)
              case None => {
                val instance = if (rCtx == ctx) {
                  new Elem(t.prefix, t.label, rAttrs, t.scope, true, deepCopy(t.child): _*)
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
              S.session.map(_.processSurroundAndInclude(PageName.get, result)) openOr result
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
  class Test
  def createTemplateNodes(ns: NodeSeq): NodeSeq = ns.map {
    case TemplateNode(e, binders) => new TemplateNode(e.prefix, e.label, e.attributes, e.scope, binders, createTemplateNodes(e.child): _*)
    case e: Elem => e.copy(child = createTemplateNodes(e.child))
    case other => other
  }

  def createTemplate(ns: NodeSeq) = new Template(createTemplateNodes(ns))
}