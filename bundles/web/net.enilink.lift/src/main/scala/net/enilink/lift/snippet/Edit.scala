package net.enilink.lift.snippet

import java.io.ByteArrayInputStream
import scala.collection.JavaConversions.bufferAsJavaList
import scala.collection.mutable.ListBuffer
import scala.xml.NodeSeq
import net.enilink.komma.model.IModel
import net.enilink.komma.model.ModelUtil
import net.enilink.komma.core.IStatement
import net.enilink.komma.core.visitor.IDataVisitor
import net.enilink.lift.util.AjaxHelpers
import net.enilink.lift.util.Globals
import net.liftweb.common.Box
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.S
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JsExp.strToJsExp
import net.liftweb.json.DefaultFormats
import net.liftweb.json._
import net.liftweb.util.JsonCommand
import net.enilink.komma.edit.properties.PropertyEditingHelper
import net.enilink.komma.edit.domain.IEditingDomainProvider
import org.eclipse.core.runtime.IStatus
import net.enilink.komma.concepts.IProperty
import net.enilink.komma.common.command.ICommand
import org.eclipse.core.runtime.Status
import net.enilink.komma.common.command.AbortExecutionException
import net.enilink.lift.Activator
import net.enilink.komma.core.Statement
import net.enilink.komma.core.IStatementPattern
import net.enilink.lift.util.TemplateHelpers
import net.liftweb.http.Templates
import net.enilink.lift.rdfa.template.RDFaTemplates
import net.enilink.lift.rdfa.template.TemplateNode
import net.enilink.komma.common.command.CommandResult
import net.liftweb.common.Full
import net.liftweb.common.Empty
import net.liftweb.util.LiftFlowOfControlException
import scala.xml.Group
import net.enilink.komma.core.IReference
import net.enilink.komma.core.URIImpl
import net.enilink.komma.edit.properties.IResourceProposal
import net.enilink.lift.rdfa.RDFaParser
import net.liftweb.json.JString

case class ProposeInput(rdf: String, query: String, index: Int)
case class GetValueInput(rdf: String)
case class SetValueInput(rdf: String, value: String, templateName: Option[String], templatePath: Option[String])

class JsonCallHandler {
  implicit val formats = DefaultFormats

  val (call, jsCmd) = AjaxHelpers.createJsonFunc(this.apply)

  val model: Box[IModel] = Globals.contextModel.vend
  val path = S.request map (_.path)

  def createHelper = new PropertyEditingHelper(false) {
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

  def apply: PartialFunction[JValue, Any] = {
    case JsonCommand("updateTriples", _, params) => {
      import scala.collection.JavaConversions._
      var successful = false
      for (
        model <- model ?~ "No active model found"
      ) {
        val em = model.getManager
        try {
          em.getTransaction.begin
          (params \ "add") match {
            case JString(add) => em.add(statements(add))
            case _ =>
          }
          // TODO recursive removal of BNodes
          (params \ "remove") match {
            case JString(remove) => em.remove(statements(remove): java.lang.Iterable[IStatementPattern])
            case _ =>
          }
          em.getTransaction.commit
          S.notice("Update was sucessful.")
          successful = true
        } catch {
          case e: Exception => if (em.getTransaction.isActive) em.getTransaction.rollback
        }
      }
      JBool(successful)
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
    case JsonCommand("setValue", _, params) => {
      import scala.collection.JavaConversions._
      import net.enilink.lift.util.TemplateHelpers._
      import net.liftweb.util.Helpers._

      lazy val okResult = JObject(Nil)
      params.extractOpt[SetValueInput] map {
        case SetValueInput(rdf, value, templateName, templatePath) =>
          statements(rdf) match {
            case stmt :: _ => {
              val cmdResult = createHelper.setValue(stmt, value.trim)
              val status = cmdResult.getStatus
              if (status.isOK) {
                templateName match {
                  case Some(tname) =>
                    val result = for {
                      p <- templatePath.filter(_.nonEmpty).map(_.stripPrefix("/").split("/").toList) orElse path.map(_.wholePath)
                      template <- TemplateHelpers.find(p, Full(tname))
                    } yield {
                      import net.enilink.lift.rdf._
                      println("Template: " + template)
                      val wrappedTemplate = <div about="?this" data-lift="rdfa">{ template }</div>
                      val resultValue = cmdResult.getReturnValues.headOption
                      val isResource = resultValue.map(_.isInstanceOf[IReference]) getOrElse false
                      val params = new RDFaParser {
                        override def createVariable(name: String) = Some(Variable(name.substring(1), None))
                        override def transformLiteral(e: xml.Elem, content: NodeSeq, literal: Literal): (xml.Elem, Node) = {
                          super.transformLiteral(e, content, literal) match {
                            case (e1, PlainLiteral(variable(l), _)) => (e1, createVariable(l).get)
                            case other => other
                          }
                        }
                      }.getArcs(wrappedTemplate, model.get.getURI.toString).flatMap {
                        case (Variable("this", _), relVar: Variable, objVar: Variable) =>
                          List((relVar.toString, stmt.getPredicate)) ++ resultValue.flatMap(v => Some((objVar.toString, v)))
                        case _ => Nil
                      }.toMap
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

  def statements(rdf: String): Seq[IStatement] = {
    val stmts = new ListBuffer[IStatement]
    ModelUtil.readData(new ByteArrayInputStream(rdf.getBytes("UTF-8")), "", null, true, new IDataVisitor[Unit]() {
      override def visitBegin {}
      override def visitEnd {}
      override def visitStatement(stmt: IStatement) = stmts += stmt
    })
    stmts.toSeq
  }
}

class Edit extends DispatchSnippet {
  val dispatch = Map("render" -> buildFuncs _)
  def buildFuncs(in: NodeSeq): NodeSeq = {
    val handler = new JsonCallHandler
    Script(handler.jsCmd &
      SetExp(JsVar("enilink"), Call("$.extend", JsRaw("window.enilink || {}"), //
        JsObj(
          ("updateTriples", AnonFunc("add, remove, callback",
            handler.call("updateTriples", JsRaw("{ 'add' : add, 'remove' : remove }"), JsVar("callback")))), //
          ("getValue", AnonFunc("rdf, callback", handler.call("getValue", JsRaw("{ 'rdf' : rdf }"), JsVar("callback")))), //
          ("setValue", AnonFunc("data, callback", handler.call("setValue", JsVar("data"), JsVar("callback")))), //
          ("propose", AnonFunc("data, callback", handler.call("propose", JsVar("data"), JsVar("callback"))) //
          )))))
  }
}