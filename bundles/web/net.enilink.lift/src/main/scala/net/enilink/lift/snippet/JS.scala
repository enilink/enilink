package net.enilink.lift.snippet

import scala.xml.NodeSeq
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.LiftRules
import scala.xml.Unparsed
import net.liftweb.http.S
import net.liftweb.http.js._
import net.liftweb.http.js.JE._
import net.liftweb.http.SHtml
import net.liftweb.http.AjaxContext
import net.liftweb.common.Full
import net.liftweb.http.Templates
import scala.xml.Group
import net.liftweb.http.JsonResponse
import net.liftweb.http.js.jquery.JqJE
import net.liftweb.http.JavaScriptResponse
import net.liftweb.json._
import scala.xml.Elem
import net.liftweb.http.NotFoundResponse

/**
 * Snippets for embedding of JS scripts.
 */
object JS extends DispatchSnippet {
  def dispatch: DispatchIt = {
    case "bootstrap" => _ => bootstrap
    case "rdfa" => _ => rdfa
    case "edit" => _ => edit
    case "templates" => _ => templates
  }

  private def script(src: String) = <script src={ src } type="text/javascript" data-lift="head"></script>

  def bootstrap: NodeSeq = List("bootstrap.min", "bootstrap-ext").flatMap {
    lib => script("/" + LiftRules.resourceServerPath + "/bootstrap/js/" + lib + ".js")
  }

  def rdfa: NodeSeq = script("/" + LiftRules.resourceServerPath + "/rdfa/jquery.rdfquery.rdfa.js")

  def edit: NodeSeq = List("jquery.autogrow", "jquery.caret").flatMap {
    lib => script("/" + LiftRules.resourceServerPath + "/edit/" + lib + ".js")
  }

  private object FindScript {
    def unapply(in: NodeSeq): Option[Elem] = in match {
      case e: Elem => e.attribute("type").map(_.text).filter(_ == "text/javascript").flatMap {
        a => if (e.attribute("src").isEmpty) Some(e) else None
      }
      case _ => None
    }
  }

  def templates: NodeSeq = JsCmds.Script(JsCmds.Function("renderTemplate", List("name", "params", "target"),
    (S.fmapFunc({ name: String =>
      Templates(name.stripPrefix("/").split("/").toList) map { ns =>
        import net.liftweb.util.Helpers._

        val html = S.session.map(s => s.fixHtml(s.processSurroundAndInclude("renderTemplate", ns))).openOr(ns)
        import scala.collection.mutable.ListBuffer
        val cmds = new ListBuffer[JsCmd]
        val revised = ("script" #> ((ns: NodeSeq) => {
          ns match {
            case FindScript(e) => {
              cmds += JE.JsRaw(ns.text).cmd
              NodeSeq.Empty
            }
            case x => x
          }
        }))(html)
        val w = new java.io.StringWriter
        S.htmlProperties.htmlWriter(Group(revised), w)
        val fields = List(JField("html", JString(w.toString))) ++ {
          if (cmds.nonEmpty) List(JField("script", JString(cmds.reduceLeft(_ & _).toJsCmd))) else Nil
        }
        JsonResponse(JObject(fields))
      } openOr JsonResponse(JObject(List()))
    }))({ name =>
      JsRaw("""var paramStr = ""; $.each(params, function (i, val) { paramStr += "&" + i + "=" + encodeURIComponent(val); })""").cmd &
        SHtml.makeAjaxCall(JsRaw("'" + name + "=' + encodeURIComponent(name) + paramStr"),
          AjaxContext.json(Full("""function(result) {
if (!result || !result.html) {
    console.log("Template '" + name + "' not found.");
    return;
}
if (typeof target === "function") {
    target(result); 
} else {
    $(target).html(result.html);
	if (result.script) {
        eval(result.script);
	}
}
}"""))).cmd
    })))
}
