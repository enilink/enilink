package net.enilink.lift.snippet

import scala.xml.NodeSeq
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.LiftRules
import scala.xml.Unparsed
import net.liftweb.http.S
import net.liftweb.http.js._
import net.liftweb.http.js.JsCmds._
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
import net.enilink.lift.util.TemplateHelpers
import java.io.ByteArrayInputStream
import net.liftweb.common.Full
import net.liftweb.common.Empty

/**
 * Snippets for embedding of JS scripts.
 */
object JS extends DispatchSnippet {
  def dispatch: DispatchIt = {
    case "bootstrap" => _ => bootstrap
    case "rdfa" => _ => rdfa
    case "templates" => _ => templates
  }

  private def script(src: String) = <script src={ src } type="text/javascript" data-lift="head"></script>

  def bootstrap: NodeSeq = List("bootstrap.min", "bootstrap-ext").flatMap {
    lib => script("/" + LiftRules.resourceServerPath + "/bootstrap/js/" + lib + ".js")
  }

  def rdfa: NodeSeq = script("/" + LiftRules.resourceServerPath + "/rdfa/jquery.rdfquery.rdfa.js")

  def templates: NodeSeq = Script(SetExp(JsVar("enilink"),
    Call("$.extend", JsRaw("window.enilink || {}"), JsObj(("renderTemplate", AnonFunc("pathOrXml, params, target",
      (S.fmapFunc({ pathOrXml: String =>
        val isXml = "\\s*<".r.findPrefixMatchOf(pathOrXml).isDefined
        (pathOrXml match {
          case xml if isXml =>
            S.htmlProperties.htmlParser(new ByteArrayInputStream(xml.getBytes("UTF-8"))) flatMap (TemplateHelpers.render(_))
          case path => TemplateHelpers.render(path.stripPrefix("/").split("/").toList)
        }) map {
          case (ns, script) => {
            import net.liftweb.util.Helpers._
            // annotate result with template path for later invocations of renderTemplate
            val nsWithPath = ns map {
              case e: Elem if !isXml => e % ("data-t-path" -> pathOrXml)
              case other => other
            }
            val w = new java.io.StringWriter
            S.htmlProperties.htmlWriter(Group(nsWithPath), w)
            val fields = List(JField("html", JString(w.toString))) ++ script.map(js => JField("script", JString(js)))
            JsonResponse(JObject(fields))
          }
        } openOr JsonResponse(JObject(List()))
      }))({ name =>
        JsRaw("""var paramStr = ""; $.each(params, function (i, val) { paramStr += "&" + i + "=" + encodeURIComponent(val); })""").cmd &
          SHtml.makeAjaxCall(JsRaw("'" + name + "=' + encodeURIComponent(pathOrXml) + paramStr"),
            AjaxContext.json(Full("""function(result) {
if (!result || !result.html) {
    console.log("Template '" + pathOrXml + "' not found or execution failed.");
    return;
}

var runScript = true;
if (typeof target === "function") {
    runScript = target(result);
} else {
    $(target).html(result.html);
}
if ((runScript === undefined || runScript) && result.script) {
    eval(result.script);
}
}"""))).cmd
      })))) // JsObj
      )) // SetExp
      ) // Script
}