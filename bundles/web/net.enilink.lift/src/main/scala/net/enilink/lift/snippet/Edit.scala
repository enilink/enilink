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

class JsonCallHandler {
  val handlers: (JsonCall, JsCmd) = S.buildJsonFunc(this.apply)
  def call: JsonCall = handlers._1
  def jsCmd: JsCmd = handlers._2

  val model: Box[IModel] = Globals.contextModel.vend

  def apply(in: Any): JsCmd = in match {
    case JsonCmd("noParam", callback, _, _) =>
      Call(callback)
    case JsonCmd("deleteTriples", callback, rdf: String, _) => {
      doDelete(rdf)
      Call(callback)
    }
    case _ => Noop
  }

  def doDelete(rdf: String) {
    for (model <- model ?~ "No active model found") {
      val em = model.getManager
      ModelUtil.readData(new ByteArrayInputStream(rdf.getBytes("UTF-8")), "", null, true, new IDataVisitor[Void]() {
        override def visitBegin = { em.getTransaction.begin; null }
        override def visitEnd = { em.getTransaction.commit; null }
        override def visitStatement(stmt: IStatement) = { em.remove(stmt); null }
      })
    }
  }
}

class Edit extends DispatchSnippet {
  val dispatch = Map("render" -> buildFuncs _)
  def buildFuncs(in: NodeSeq): NodeSeq = {
    val handler = new JsonCallHandler
    Script(handler.jsCmd &
      Function("noParam", List("callback"),
        handler.call("noParam",
          JsVar("callback"),
          JsObj()))
        & Function("deleteTriples", List("callback", "rdf"),
          handler.call("deleteTriples",
            JsVar("callback"),
            JsVar("rdf"))))
  }
}