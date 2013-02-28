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
import net.liftweb.http.js.JE.JsObj
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.http.js.JE.JsVar
import net.liftweb.http.js.JsCmds.Function
import net.liftweb.http.js.JsCmds.Script
import net.liftweb.http.js.JsCmds.jsExpToJsCmd
import net.liftweb.http.js.JsExp.strToJsExp
import net.liftweb.json.DefaultFormats
import net.liftweb.json.JBool
import net.liftweb.json.JString
import net.liftweb.json.JValue
import net.liftweb.util.JsonCommand

class JsonCallHandler {
  implicit val formats = DefaultFormats

  val (call, jsCmd) = AjaxHelpers.createJsonFunc(this.apply)

  val model: Box[IModel] = Globals.contextModel.vend

  def apply: PartialFunction[JValue, JValue] = {
    case JsonCommand("noParam", _, _) =>
      S.notice("noParam"); JBool(true)
    case JsonCommand("updateTriples", _, params) => {
      var successful = false
      for (
        model <- model ?~ "No active model found"
      ) {
        val em = model.getManager
        try {
          em.getTransaction.begin
          (params \ "add") match {
            case JString(add) => process(add, em.add _)
            case _ =>
          }
          // TODO recursive removal of BNodes
          (params \ "remove") match {
            case JString(remove) => process(remove, em.remove _)
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
  }

  def process(rdf: String, f: java.lang.Iterable[_ <: IStatement] => Unit) {
    import scala.collection.JavaConversions._
    val stmts = new ListBuffer[IStatement]
    ModelUtil.readData(new ByteArrayInputStream(rdf.getBytes("UTF-8")), "", null, true, new IDataVisitor[Unit]() {
      override def visitBegin {}
      override def visitEnd {}
      override def visitStatement(stmt: IStatement) = stmts += stmt
    })
    f(stmts)
  }
}

class Edit extends DispatchSnippet {
  val dispatch = Map("render" -> buildFuncs _)
  def buildFuncs(in: NodeSeq): NodeSeq = {
    val handler = new JsonCallHandler
    Script(handler.jsCmd &
      Function("noParam", List("callback"), handler.call("noParam", JsObj(), JsVar("callback")))
      & Function("updateTriples", List("callback", "add", "remove"),
        handler.call("updateTriples", JsRaw("{ 'add' : add, 'remove' : remove }"), JsVar("callback"))))
  }
}