package net.enilink.lift.util

import net.liftweb.http.S._
import net.liftweb.http.js.JsExp
import net.liftweb.http.js.JsCmd
import net.liftweb.common._
import net.liftweb.http.js.JsCmds
import net.liftweb.http.LiftRules
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsExp._
import net.liftweb.http.SHtml
import net.liftweb.http.AjaxContext
import net.liftweb.json.JsonParser
import net.liftweb.json.JsonAST._
import net.liftweb.http.JsonResponse

object AjaxHelpers {
  case class JsonFunc(funcId: String) {
    def apply(command: JsExp, params: JsExp, callback: JsExp = null, httpParams: JsExp = null) = {
      var args: Seq[JsExp] = Seq(JsRaw("{'command': " + command.toJsCmd + ", 'params':" + params.toJsCmd + "}"))
      if (callback != null) args ++= Seq(callback)
      if (httpParams != null) args ++= Seq(httpParams)
      Call(funcId, args: _*)
    }
  }

  /**
   * Build a handler for incoming JSON commands based on the new Json Parser
   *
   * @param f - partial function against a returning a JsCmds
   *
   * @return ( JsonCall, JsCmd )
   */
  def createJsonFunc(f: PFPromoter[JValue, _ <: Any]): (JsonFunc, JsCmd) = createJsonFunc(Empty, f)

  /**
   * Build a handler for incoming JSON commands based on the new Json Parser
   *
   * @param onError -- the JavaScript to execute client-side if the request is not processed by the server
   * @param f - partial function against a returning a JsCmds
   *
   * @return ( JsonCall, JsCmd )
   */
  def createJsonFunc(onError: JsCmd, f: PFPromoter[JValue, _ <: Any]): (JsonFunc, JsCmd) = createJsonFunc(Full(onError), f)

  /**
   * Build a handler for incoming JSON commands based on the new Json Parser.  You
   * can use the helpful Extractor in net.liftweb.util.JsonCommand
   *
   * @param onError -- the JavaScript to execute client-side if the request is not processed by the server
   * @param f - partial function against a returning a JsCmds
   *
   * @return ( JsonCall, JsCmd )
   */
  def createJsonFunc(onError: Box[JsCmd], pfp: PFPromoter[JValue, _ <: Any]): (JsonFunc, JsCmd) = {
    def jsonCallback(in: List[String]): Any = {
      val f = pfp.pff()
      val result = for {
        line <- in
        parsed <- JsonParser.parseOpt(line) if f.isDefinedAt(parsed)
      } yield f(parsed)
      val jsCmds = result.collect { case cmd: JsCmd => cmd } ++ List(LiftRules.noticesToJsCmd())
      val jsonObjs = result.collect { case v: JValue => v }
      JsonResponse(JsObj(
        ("result", if (jsonObjs.isEmpty || jsonObjs.size > 1) JArray(jsonObjs) else jsonObjs.head),
        ("script", jsCmds.reduceLeft(_ & _).toJsCmd)))
    }

    val af: AFuncHolder = jsonCallback _
    functionLifespan(true) {
      fmapFunc(af)({ name =>
        val body = JsRaw("""var paramStr = ""; if (httpParams) $.each(httpParams, function (i, val) { paramStr += "&" + i + "=" + encodeURIComponent(val); })""").cmd &
          SHtml.makeAjaxCall(JsRaw("'" + name + "=' + encodeURIComponent(" + LiftRules.jsArtifacts.jsonStringify(JsRaw("obj")).toJsCmd + ") + paramStr"),
            AjaxContext.json(
              Full("""function(json) {
if (json) {
    if (json.result !== undefined && typeof callback === "function") {
        callback(json.result);
    }
    if (json.script) {
        eval(json.script);
    }
}
}"""), onError.map(f => JsCmds.Run("function () { " + f.toJsCmd + " }") //
))).cmd

        (JsonFunc(name), JsCmds.Function(name, List("obj", "callback", "httpParams"), body))
      })
    }
  }
}