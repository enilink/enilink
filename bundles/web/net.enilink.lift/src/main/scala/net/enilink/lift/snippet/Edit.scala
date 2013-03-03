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

class JsonCallHandler {
  implicit val formats = DefaultFormats

  val (call, jsCmd) = AjaxHelpers.createJsonFunc(this.apply)

  val model: Box[IModel] = Globals.contextModel.vend

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
    
    override def getPropertyEditingSupport(stmt : IStatement) = {
      super.getPropertyEditingSupport(stmt)
    }

    override def setProperty(element: Any, property: IProperty) {}

    override def execute(command: ICommand) = command match {
      case c: ICommand if c.canExecute => try {
        c.execute(null, null)
      } catch {
        case e: AbortExecutionException =>
          command.dispose
          Status.CANCEL_STATUS
        case rte: RuntimeException =>
          command.dispose
          new Status(IStatus.ERROR, Activator.PLUGIN_ID, "Command execution failed", rte)
      }
      case c: ICommand =>
        c.dispose
        Status.CANCEL_STATUS
      case _ => Status.CANCEL_STATUS
    }
  }

  def apply: PartialFunction[JValue, JValue] = {
    case JsonCommand("noParam", _, _) =>
      S.notice("noParam"); JBool(true)
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
    case JsonCommand("setValue", _, params) => {
      case class Data(rdf: String, editorValue: String)
      params.extractOpt[Data] map {
        case Data(rdf, editorValue) =>
          statements(rdf) match {
            case stmt :: _ => {
              val status = createHelper.setValue(stmt, editorValue)
              if (status.isOK) JNull else JString(status.getMessage)
            }
            case _ => JNull
          }
      } getOrElse JNull
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
      Function("noParam", List("callback"), handler.call("noParam", JsObj(), JsVar("callback")))
      & Function("updateTriples", List("add", "remove", "callback"),
        handler.call("updateTriples", JsRaw("{ 'add' : add, 'remove' : remove }"), JsVar("callback")))
        & Function("setValue", List("rdf", "editorValue", "callback"),
          handler.call("setValue", JsRaw("{ 'rdf' : rdf, 'editorValue' : editorValue }"), JsVar("callback"))))
  }
}