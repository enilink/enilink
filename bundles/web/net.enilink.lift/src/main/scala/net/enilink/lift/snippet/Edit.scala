package net.enilink.lift.snippet

import java.io.ByteArrayInputStream
import scala.collection.JavaConversions.bufferAsJavaList
import scala.collection.mutable.LinkedHashSet
import scala.collection.mutable.ListBuffer
import scala.collection.{ mutable }
import scala.xml.Group
import scala.xml.NodeSeq
import org.eclipse.core.runtime.IStatus
import net.enilink.komma.common.command.AbortExecutionException
import net.enilink.komma.common.command.CommandResult
import net.enilink.komma.common.command.ICommand
import net.enilink.komma.concepts.IProperty
import net.enilink.komma.concepts.IResource
import net.enilink.komma.edit.domain.IEditingDomainProvider
import net.enilink.komma.edit.properties.IResourceProposal
import net.enilink.komma.edit.properties.PropertyEditingHelper
import net.enilink.komma.edit.util.PropertyUtil
import net.enilink.komma.model.IModel
import net.enilink.komma.model.ModelUtil
import net.enilink.komma.core.BlankNode
import net.enilink.komma.core.IEntityManager
import net.enilink.komma.core.IReference
import net.enilink.komma.core.IStatement
import net.enilink.komma.core.IStatementPattern
import net.enilink.komma.core.Statement
import net.enilink.komma.core.URIImpl
import net.enilink.komma.core.visitor.IDataVisitor
import net.enilink.lift.rdfa.RDFaParser
import net.enilink.lift.rdfa.template.RDFaTemplates
import net.enilink.lift.util.AjaxHelpers
import net.enilink.lift.util.Globals
import net.enilink.lift.util.TemplateHelpers
import net.liftweb.common.Box
import net.liftweb.common.Full
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.S
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JsExp.strToJsExp
import net.liftweb.json._
import net.liftweb.json.JsonDSL
import net.liftweb.util.JsonCommand
import net.liftweb.util.LiftFlowOfControlException

case class ProposeInput(rdf: JValue, query: String, index: Int)
case class GetValueInput(rdf: JValue)
case class SetValueInput(rdf: JValue, value: JValue, template: Option[String], what: Option[String])

class JsonCallHandler {
  implicit val formats = DefaultFormats

  val (call, jsCmd) = AjaxHelpers.createJsonFunc(this.apply)

  val model: Box[IModel] = Globals.contextModel.vend
  val path = S.request map (_.path)

  class EditingHelper extends PropertyEditingHelper(false) {
    override def getStatement(element: AnyRef) = {
      val stmt = element.asInstanceOf[IStatement]
      val em = model.get.getManager
      new Statement(em.find(stmt.getSubject), em.find(stmt.getPredicate), stmt.getObject)
    }

    override def getEditingDomain = model.get.getModelSet.adapters.getAdapter(classOf[IEditingDomainProvider]) match {
      case p: IEditingDomainProvider => p.getEditingDomain
      case _ => null
    }

    override def getPropertyEditingSupport(stmt: IStatement) = {
      super.getPropertyEditingSupport(stmt)
    }

    override def setProperty(element: Any, property: IProperty) {}

    override def execute(command: ICommand) = command match {
      case c: ICommand if c.canExecute => try {
        c.execute(null, null)
        c.getCommandResult
      } catch {
        case e: AbortExecutionException =>
          command.dispose
          CommandResult.newCancelledCommandResult
        case rte: RuntimeException =>
          command.dispose
          CommandResult.newErrorCommandResult(rte)
      }
      case c: ICommand =>
        c.dispose
        CommandResult.newCancelledCommandResult
      case _ => CommandResult.newCancelledCommandResult
    }
  }

  def createHelper = new EditingHelper

  def apply: PartialFunction[JValue, Any] = {
    case JsonCommand("removeResource", _, JString(resource)) => {
      (for (model <- model; em = model.getManager) yield {
        val ref = if (resource.startsWith("_:")) em.createReference(resource)
        else if (resource.startsWith("<") && resource.endsWith(">")) URIImpl.createURI(resource.substring(1, resource.length - 1))
        else URIImpl.createURI(resource)
        em.removeRecursive(ref, true);
        JBool(true)
      }) openOr JBool(false)
    }
    case JsonCommand("blankNode", _, _) => {
      (for (model <- model; em = model.getManager) yield em.createReference.toString) or
        Some(new BlankNode().toString) map (JString(_)) get
    }
    case JsonCommand("updateTriples", _, params) => {
      import scala.collection.JavaConversions._
      var success = false
      var subject: Option[String] = None
      for (
        model <- model ?~ "No active model found"
      ) {
        val em = model.getManager
        try {
          em.getTransaction.begin
          (params \ "add") match {
            case JNothing =>
            case add: JValue => {
              val replacements = new mutable.HashMap[String, IReference]
              val stmts = replaceBNodes(statements(add), em, replacements)
              (params \ "subject") match {
                // return the mapped subject as a result
                case JString(s) => subject = replacements.get(s).map(_.toString) orElse Some(s)
                case _ =>
              }
              em.add(stmts)
            }
          }
          // TODO recursive removal of BNodes
          (params \ "remove") match {
            case JNothing =>
            case remove: JValue => em.remove(statements(remove): java.lang.Iterable[IStatementPattern])
          }
          em.getTransaction.commit
          S.notice("Update was sucessful.")
          success = true
        } catch {
          case e: Exception => if (em.getTransaction.isActive) em.getTransaction.rollback
        }
      }
      if (success) subject.map(s => JString(s.toString())).getOrElse(JBool(true)) else JBool(false)
    }
    case JsonCommand("propose", _, params) => {
      import net.liftweb.json.JsonDSL._
      val proposals = for (
        ProposeInput(rdf, query, index) <- params.extractOpt[ProposeInput];
        stmt <- statements(rdf).headOption;
        proposalSupport <- Option(createHelper.getProposalSupport(stmt));
        proposalProvider <- Option(proposalSupport.getProposalProvider)
      ) yield {
        proposalProvider.getProposals(query, index) map { p =>
          val o = ("label", p.getLabel) ~ ("content", p.getContent) ~ ("description", p.getDescription) ~
            ("cursorPosition", p.getCursorPosition) ~ ("insert", p.isInsert)
          p match {
            case resProposal: IResourceProposal if resProposal.getUseAsValue => o ~ ("resource", resProposal.getResource.getReference.toString)
            case other => o
          }
        } toList
      }
      proposals map (JArray(_)) getOrElse JArray(Nil)
    }
    case JsonCommand("getValue", _, params) => {
      params.extractOpt[GetValueInput] flatMap {
        case GetValueInput(rdf) => statements(rdf) match {
          case stmt :: _ => Option(createHelper.getValue(stmt))
          case _ => None
        }
        case _ => None
      } map (v => JString(v.toString)) getOrElse JString("")
    }
    case JsonCommand("removeValue", _, rdf) => {
      import scala.collection.JavaConversions._
      var successful = false
      for (
        model <- model ?~ "No active model found"
      ) {
        val em = model.getManager
        val helper = createHelper
        val editingDomain = helper.getEditingDomain
        try {
          em.getTransaction.begin
          statements(rdf) match {
            case stmt :: _ => {
              val removeCommand = PropertyUtil.getRemoveCommand(
                editingDomain,
                em.find(stmt.getSubject, classOf[IResource]),
                em.find(stmt.getPredicate, classOf[IProperty]),
                stmt.getObject);
              successful &= helper.execute(removeCommand).getStatus.isOK
            }
          }
          em.getTransaction.commit
          successful = true
        } catch {
          case e: Exception => if (em.getTransaction.isActive) em.getTransaction.rollback
        }
      }
      JBool(successful)
    }
    case JsonCommand("setValue", _, params) => {
      import scala.collection.JavaConversions._
      import net.enilink.lift.util.TemplateHelpers._
      import net.liftweb.util.Helpers._

      lazy val okResult = JObject(Nil)
      params.extractOpt[SetValueInput] map {
        case SetValueInput(rdf, value, template, templatePath) =>
          statements(rdf) match {
            case stmt :: _ => {
              val cmdResult = createHelper.setValue(stmt, value match {
                case JString(s) => s.trim
                // also allow RDF/JSON encoded values
                case _ => valueFromJSON(value)
              })
              val status = cmdResult.getStatus
              if (status.isOK) {
                template match {
                  case Some(tname) =>
                    val result = for {
                      p <- templatePath.filter(_.nonEmpty).map(_.stripPrefix("/").split("/").toList) orElse path.map(_.wholePath)
                      template <- TemplateHelpers.find(p, Full(tname))
                    } yield {
                      import net.enilink.lift.rdf._
                      println("Template: " + template)
                      val wrappedTemplate = <div about="?this" data-lift="rdfa">{ template }</div>
                      val resultValue = cmdResult.getReturnValues.headOption
                      val vars = new LinkedHashSet[Variable]()
                      var params = new RDFaParser {
                        override def createVariable(name: String) = {
                          val v = Variable(name.substring(1), None)
                          vars.add(v)
                          Some(v)
                        }
                        override def transformLiteral(e: xml.Elem, content: NodeSeq, literal: Literal): (xml.Elem, Node) = {
                          super.transformLiteral(e, content, literal) match {
                            case (e1, PlainLiteral(variable(l), _)) => (e1, createVariable(l).get)
                            case other => other
                          }
                        }
                      }.getArcs(wrappedTemplate, model.get.getURI.toString).flatMap {
                        // TODO support reverse relationships
                        case (Variable("this", _), rel, objVar: Variable) => (
                          rel match {
                            case v: Variable => List((v.toString, stmt.getPredicate))
                            case _ => Nil
                          }) ++ resultValue.flatMap(v => Some((objVar.toString, v)))
                        case _ => Nil
                      }.toMap
                      // [rel] or [rev] was not contained in current HTML fragment
                      // simply bind first var that is different from ?this
                      if (params.isEmpty) params = resultValue flatMap { value =>
                        vars.collectFirst { case v if v.n != "this" => v } map { v => (v.toString, value) }
                      } toMap
                      val renderResult = Globals.contextResource.doWith(Full(model.get.getManager.find(stmt.getSubject))) {
                        QueryParams.doWith(params) { TemplateHelpers.withAppFor(p)(TemplateHelpers.render(wrappedTemplate)) }
                      }
                      renderResult match {
                        case Full((html, script)) =>
                          val w = new java.io.StringWriter
                          S.htmlProperties.htmlWriter(Group(html \ "_"), w)
                          List(JObject(List(JField("html", JString(w.toString))))) ++ script.map(Run(_))
                        case _ => okResult
                      }
                    }
                    result getOrElse okResult
                  case _ => okResult
                }
              } else JObject(List(JField("msg", JString(status.getMessage))))
            }
            case _ => okResult
          }
      } getOrElse okResult
    }
  }

  /**
   * Parses RDF JSON Alternate Serialization (RDF/JSON) in the form { "S" : { "P" : [ O ] } }.
   */
  def statementsFromJSON(rdf: JObject): Seq[IStatement] = {
    def toResource(ref: String) = if (ref.startsWith("_:")) new BlankNode(ref) else URIImpl.createURI(ref)
    rdf.children flatMap {
      case JField(s, po: JObject) =>
        val sResource = toResource(s)
        po.children flatMap {
          case JField(p, JArray(objs)) => objs map { o =>
            val pResource = toResource(p)
            val stmt = new Statement(sResource, pResource, valueFromJSON(o))
            stmt
          }
          case _ => Nil
        }
      case _ => Nil
    }
  }

  def valueFromJSON(o: JValue) = {
    import net.enilink.komma.core.Literal
    val oValue = (o \ "value").values.toString
    (o \ "type") match {
      case JString("uri") => URIImpl.createURI(oValue)
      case JString("bnode") => new BlankNode(oValue)
      case _ /* JString("literal") */ => (o \ "datatype") match {
        case JString(datatype) => new Literal(oValue, URIImpl.createURI(datatype))
        case _ => (o \ "lang") match {
          case JString(lang) => new Literal(oValue, lang)
          case _ => new Literal(oValue)
        }
      }
    }
  }

  /**
   * Parses RDF/JSON, RDF/XML and Turtle.
   */
  def statements(rdf: JValue): Seq[IStatement] = rdf match {
    case rdfJson: JObject => statementsFromJSON(rdfJson)
    case JString(rdfStr) =>
      val stmts = new ListBuffer[IStatement]
      ModelUtil.readData(new ByteArrayInputStream(rdfStr.getBytes("UTF-8")), "", null, true, new IDataVisitor[Unit]() {
        override def visitBegin {}
        override def visitEnd {}
        override def visitStatement(stmt: IStatement) = stmts += stmt
      })
      stmts.toSeq
    case _ => Nil
  }

  /** Replace blank nodes with prefix "new" by new blank nodes generated by an entity manager. */
  def replaceBNodes(stmts: Seq[IStatement], em: IEntityManager, replacements: mutable.Map[String, IReference] = new mutable.HashMap) = {
    def replace[T](node: T): T = (node match {
      case ref: IReference => if (ref.getURI == null && ref.toString.startsWith("_:new")) replacements.getOrElseUpdate(ref.toString, em.createReference) else ref
      case other => other
    }).asInstanceOf[T]
    stmts map { stmt =>
      val (s, p, o) = (stmt.getSubject, stmt.getPredicate, stmt.getObject)
      val (s2, p2, o2) = (replace(s), replace(p), replace(o))
      if ((s ne s2) || (p ne p2) || (o ne o2)) new Statement(s2, p2, o2, stmt.getContext) else stmt
    }
  }
}

class Edit extends DispatchSnippet {
  val dispatch = Map("render" -> buildFuncs _)
  def buildFuncs(in: NodeSeq): NodeSeq = {
    val handler = new JsonCallHandler
    Script(handler.jsCmd &
      SetExp(JsVar("enilink"), Call("$.extend", JsRaw("window.enilink || {}"), //
        JsObj(
          ("blankNode", AnonFunc("callback", handler.call("blankNode", JsRaw("{}"), JsVar("callback")))), //
          ("removeResource", AnonFunc("resource, callback", handler.call("removeResource", JsVar("resource"), JsVar("callback")))), //
          ("updateTriples", AnonFunc("add, remove, callback",
            handler.call("updateTriples", JsRaw("(add.add !== undefined || add.remove !== undefined) ? add : { 'add' : add, 'remove' : remove }"), JsRaw("typeof remove === 'function' ? remove : callback")))), //
          ("getValue", AnonFunc("rdf, callback", handler.call("getValue", JsRaw("{ 'rdf' : rdf }"), JsVar("callback")))), //
          ("setValue", AnonFunc("data, callback", handler.call("setValue", JsVar("data"), JsVar("callback")))), //
          ("removeValue", AnonFunc("rdf, callback", handler.call("removeValue", JsVar("rdf"), JsVar("callback")))), //
          ("propose", AnonFunc("data, callback", handler.call("propose", JsVar("data"), JsVar("callback"))) //
          )))))
  }
}