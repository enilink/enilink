package net.enilink.platform.lift.snippet

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
import net.liftweb.common.{Box, Empty, Full}

import scala.xml.Group
import net.liftweb.http.JsonResponse
import net.liftweb.http.js.jquery.JqJE
import net.liftweb.http.JavaScriptResponse
import net.liftweb.json._

import scala.xml.Elem
import net.liftweb.http.NotFoundResponse
import java.io.ByteArrayInputStream

import net.liftweb.common.Full
import net.enilink.platform.lift.util.AjaxHelpers
import net.liftweb.json.DefaultFormats
import net.liftweb.json._
import net.liftweb.util.JsonCommand
import net.enilink.platform.lift.util.Globals
import net.liftweb.http.Req

case class RenderInput(what: Option[String], template: Option[String], bind: Option[JObject], rdfa : Option[Boolean])

/**
 * Snippets for embedding of JS scripts.
 */
object JS extends DispatchSnippet with SparqlHelper {
  def dispatch: DispatchIt = {
    case "bootstrap" => _ => bootstrap
    case "rdfa" => _ => rdfa
    case "require" => _ => {
      <script type="text/javascript">var require = {{ baseUrl : "/classpath/" }};</script> ++
        <script src={ "/" + LiftRules.resourceServerPath + "/require/require.js" } type="text/javascript"></script>
    }

    case "templates" => _ => <script src={ "/" + LiftRules.resourceServerPath + "/enilink/enilink.js" } type="text/javascript"></script> ++ templates
  }

  private def script(src: String) = <script src={ src } type="text/javascript" data-lift="head"></script>

  def bootstrap: NodeSeq = List("/bootstrap/js/bootstrap.min", "/bootstrap/js/bootstrap-ext", "/typeaheadjs/typeahead.bundle").flatMap {
    lib => script("/" + LiftRules.resourceServerPath + lib + ".js")
  }

  def rdfa: NodeSeq = script("/" + LiftRules.resourceServerPath + "/rdfa/jquery.rdfquery.rdfa.js")

  implicit val formats = DefaultFormats
  def ajax: PartialFunction[JValue, Any] = {
    case JsonCommand("render", _, params) =>
      val result = for (
        RenderInput(what, templateName, bind, useRdfa) <- params.extractOpt[RenderInput]
      ) yield {
        import net.enilink.platform.lift.util.TemplateHelpers._

        def renderWithParams(ns: NodeSeq): Box[(NodeSeq, Box[String])] = {
          val paramMap = bind map { b => convertParams(b.values) }
          paramMap map { QueryParams.doWith(_)(render(ns)) } getOrElse render(ns)
        }

        val isXml = what.exists("\\s*<".r.findPrefixMatchOf(_).isDefined)
        lazy val parsePath = what.map(Req.parsePath(_)) orElse S.originalRequest.map(_.path)
        (what match {
          case Some(xml) if isXml =>
            S.htmlProperties.htmlParser(new ByteArrayInputStream(xml.getBytes("UTF-8"))) flatMap (renderWithParams(_))
          case other => parsePath map { path =>
            val pathList = path.partPath
            withAppFor(pathList) {
              // rdfa processing is enabled by default and can be explicitly disabled
              val useRdfaProcessing = useRdfa.isEmpty || useRdfa.exists(_ == true)
              var wrapped = false
              find(pathList, templateName) map { ns =>
                // wrap with data-lift="rdfa" for RDFa processing
                if (useRdfaProcessing && templateName.isDefined && Globals.contextModel.vend.isDefined) ns match {
                  case e: Elem if !e.attribute("data-lift").exists(_.text.matches("^rdfa\\??")) => {
                    wrapped = true
                    <div data-lift="rdfa">
                      {ns}
                    </div>
                  }
                  case other => other
                } else ns
              } flatMap (renderWithParams(_)) map {
                case (ns, js) if wrapped => {
                  // remove rdfa wrapper
                  (ns \ "_", js)
                }
                case other => other
              }
            }
          } getOrElse Empty
        }) map {
          case (ns, script) => {
            import net.liftweb.util.Helpers._
            // annotate result with template path for later invocations of render
            val nsWithPath = ns map {
              case e: Elem if !isXml => e % ("data-t-path" -> parsePath.map(_.partPath.mkString("/")).getOrElse(""))
              case other => other
            }
            val w = new java.io.StringWriter
            S.htmlProperties.htmlWriter(Group(nsWithPath), w)
            List(JObject(List(JField("html", JString(w.toString))))) ++ script.map(JsCmds.Run(_))
          }
        } openOr JObject(Nil)
      }
      result getOrElse JObject(Nil)
  }

  def templates: NodeSeq = {
    val (call, jsCmd) = S.functionLifespan(true) {
      AjaxHelpers.createJsonFunc(getClass.getName, AjaxContext.json(Full("""function(response) {
var result = response.result;
if (result === undefined || result.html === undefined) {
    console.log("Template '" + obj.params.templatePath + "' not found or execution failed.");
    return;
}

var runScript = true;
if (typeof callback === "function") {
    runScript = callback(result.html, response.script);
} else {
    $(callback).html(result.html);
}
if ((runScript === undefined || runScript) && response.script) {
    eval(response.script);
}
}""")), this.ajax)
    }

    Script(jsCmd &
      SetExp(JsVar("enilink"), Call("$.extend", JsRaw("window.enilink || {}"), //
        JsObj(
          ("render", AnonFunc("pathOrXml, httpParams, target",
            call("render", JsRaw("(typeof pathOrXml === 'object') ? pathOrXml : { 'what' : pathOrXml }"),
              JsVar("target"), JsVar("httpParams"))) // 
              ) //
              ))))
  }
}