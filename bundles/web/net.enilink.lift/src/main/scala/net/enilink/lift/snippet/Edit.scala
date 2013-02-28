package net.enilink.lift.snippet

import java.io.ByteArrayInputStream
import scala.xml.NodeSeq
import net.enilink.komma.model.ModelUtil
import net.enilink.komma.core.visitor.IDataVisitor
import net.liftweb.common._
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.JsonHandler
import net.liftweb.http.SessionVar
import net.liftweb.http.js.JE.Call
import net.liftweb.http.js.JE.JsObj
import net.liftweb.http.js.JE.JsVar
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JsCmds.Function
import net.liftweb.http.js.JsCmds.Noop
import net.liftweb.http.js.JsCmds.Script
import net.liftweb.http.js.JsCmds.jsExpToJsCmd
import net.liftweb.http.js.JsExp.strToJsExp
import net.liftweb.util.JsonCmd
import net.enilink.komma.core.IStatement
import net.enilink.lift.util.Globals
import net.liftweb.http.RequestVar
import net.enilink.komma.model.IModel
import net.liftweb.http.StatefulSnippet
import net.liftweb.http.S
import net.liftweb.http.js.JsonCall
import scala.collection.mutable.ListBuffer
import net.liftweb.json.JsonDSL
import net.liftweb.json.JValue
import net.liftweb.json.JValue
import net.liftweb.http.js.JE.JsRaw

case class JsonCallExt(override val funcId : String) extends JsonCall(funcId) {
  
}

class JsonCallHandler {
  val handlers: (JsonCall, JsCmd) = S.buildJsonFunc(this.apply)
  def call: JsonCall = JsonCallExt(handlers._1.funcId)
  def jsCmd: JsCmd = handlers._2

  val model: Box[IModel] = Globals.contextModel.vend

  def apply(in: Any): JsCmd = in match {
    case JsonCmd("noParam", callback, _, _) =>
      Call(callback)
    case JsonCmd("updateTriples", callback, params: Map[String, String], _) => {
      var successful = false
      for (
        model <- model ?~ "No active model found"
      ) {
        val em = model.getManager
        try {
          em.getTransaction.begin
          params.get("add") filter (_.nonEmpty) foreach { add => process(add, em.add _) }
          // TODO recursive removal of BNodes
          params.get("remove") filter (_.nonEmpty) foreach { remove => process(remove, em.remove _) }
          em.getTransaction.commit
          S.notice("Update was sucessful.")
          successful = true
        } catch {
          case e: Exception => if (em.getTransaction.isActive) em.getTransaction.rollback
        }
      }
      Call(callback, successful)
    }
    case _ => Noop
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
      Function("noParam", List("callback"), handler.call("noParam", JsVar("callback"), JsObj()))
      & Function("updateTriples", List("callback", "add", "remove"),
        handler.call("updateTriples", JsVar("callback"), JsRaw("{ 'add' : add, 'remove' : remove }"))))
  }
}