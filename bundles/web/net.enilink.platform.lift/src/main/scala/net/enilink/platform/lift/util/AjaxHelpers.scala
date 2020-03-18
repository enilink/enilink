package net.enilink.platform.lift.util

import net.liftweb.http.S
import net.liftweb.http.S._
import net.liftweb.http.js.JsExp
import net.liftweb.http.js.JsCmd
import net.liftweb.common._
import net.liftweb.http.LiftRules
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JsExp
import net.liftweb.http.SHtml
import net.liftweb.http.AjaxContext
import net.liftweb.json.JsonParser
import net.liftweb.json.JsonAST._
import net.liftweb.http.JsonResponse
import net.liftweb.http.JsonContext
import net.liftweb.http.SessionVar
import scala.collection.Map
import scala.xml._
import net.liftweb.http.SHtml
import net.liftweb.http.SHtml.ElemAttr

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
  def createJsonFunc(f: PFPromoter[JValue, _ <: Any]): (JsonFunc, JsCmd) = createJsonFunc(AjaxContext.json(
    Full("""function(response) {
if (response) {
    var runScript = true;
    if (response.result !== undefined && typeof callback === "function") {
        runScript = callback(response.result);
    }
    if ((runScript === undefined || runScript) && response.script) {
        eval(response.script);
    }
}
}""")), f)

  /**
   * Build a handler for incoming JSON commands based on the new Json Parser. You
   * can use the helpful Extractor in net.liftweb.util.JsonCommand
   *
   * @param ajaxContent -- the JavaScript to execute client-side if the request succeeds or fails
   * @param f - partial function against a returning a JsCmds
   *
   * @return ( JsonCall, JsCmd )
   */
  def createJsonFunc(ajaxContext: JsonContext, pfp: PFPromoter[JValue, _ <: Any]): (JsonFunc, JsCmd) = {
    import net.liftweb.json.JsonDSL._
    def jsonCallback(in: List[String]): Any = {
      val f = pfp.pff()
      val objects: List[JObject] = in.flatMap { line => JsonParser.parseOpt(line) } flatMap {
        parsed =>
          if (f.isDefinedAt(parsed)) {
            // add individual result object for each function
            val result = (f(parsed) match {
              case seq: Seq[_] => seq.toList
              case other => List(other)
            })
            val jsCmds = result.collect { case cmd: JsCmd => cmd } ++ List(LiftRules.noticesToJsCmd())
            val jsonObjs = result.collect { case v: JValue => v }
            Some(("result", if (jsonObjs.isEmpty || jsonObjs.size > 1) JArray(jsonObjs) else jsonObjs.head) ~
              ("script", jsCmds.reduceLeft(_ & _).toJsCmd))
          } else Nil
      }
      JsonResponse(if (objects.isEmpty || objects.size > 1) JArray(objects) else objects.head)
    }

    val af: AFuncHolder = jsonCallback _
    fmapFunc(af)({ name =>
      val body = JsRaw("""var paramStr = ""; if (httpParams) $.each(httpParams, function (i, val) { paramStr += "&" + i + "=" + encodeURIComponent(val); })""").cmd &
        SHtml.makeAjaxCall(JsRaw("'" + name + "=' + encodeURIComponent(" + LiftRules.jsArtifacts.jsonStringify(JsRaw("obj")).toJsCmd + ") + paramStr"),
          ajaxContext).cmd

      (JsonFunc(name), Function(name, List("obj", "callback", "httpParams"), body))
    })
  }

  object cachedFuncs extends SessionVar[Map[String, (JsonFunc, JsCmd)]](Map.empty)

  /**
   * Build or reuse a handler for incoming JSON commands based on the new Json Parser. You
   * can use the helpful Extractor in net.liftweb.util.JsonCommand
   *
   * @param name -- the name of the handler which is used as a cache key
   * @param ajaxContent -- the JavaScript to execute client-side if the request succeeds or fails
   * @param f - partial function against a returning a JsCmds
   *
   * @return ( JsonCall, JsCmd )
   */
  def createJsonFunc(name: String, ajaxContext: JsonContext, pfp: PFPromoter[JValue, _ <: Any]): (JsonFunc, JsCmd) = {
    cachedFuncs.get.get(name) match {
      case Some((func, cmd)) => (func, cmd)
      case _ =>
        val ret = createJsonFunc(ajaxContext, pfp)
        cachedFuncs.set(cachedFuncs.get + (name -> ret))
        ret
    }
  }

  /**
   * Specify the events (e.g., onblur, onchange, etc.)
   * and the function to execute on those events.  Returns
   * a NodeSeq => NodeSeq that will add the events to all
   * the Elements
   * <code>
   * ":text" #> AjaxHelpers.onEvents("onchange", "onblur", Run("alert('Hello World!')"))
   * </code>
   */
  def onEvents(events: List[String], cmd: JsCmd): NodeSeq => NodeSeq = {
    ns =>
      {
        def runNodes(in: NodeSeq): NodeSeq = in.flatMap {
          case Group(g) => runNodes(g)
          case e: Elem => {
            val oldAttr: Map[String, String] = Map(
              events.flatMap(a => e.attribute(a).map(v => a -> (v.text + "; "))): _*)
            val newAttr = e.attributes.filter {
              case up: UnprefixedAttribute => !oldAttr.contains(up.key)
              case _ => true
            }
            e.copy(attributes = events.foldLeft(newAttr) {
              case (meta, attr) => new UnprefixedAttribute(attr, oldAttr.getOrElse(attr, "") + cmd.toJsCmd, meta)
            })
          }
          case other => other
        }
        runNodes(ns)
      }
  }

  def onEvents(events: List[String], func: String => JsCmd): NodeSeq => NodeSeq = onEvents(events, Str("true"), func)

  def onEvents(events: List[String], param: JsExp, func: String => JsCmd): NodeSeq => NodeSeq = {
    onEvents(events, SHtml.ajaxCall(param, func)._2)
  }

  def deferCall(data: JsExp, jsFunc: Call, ajaxContext: AjaxContext): Call =
    Call(jsFunc.function, (jsFunc.params ++ List(AnonFunc(SHtml.makeAjaxCall(data, ajaxContext)))): _*)

  def onEventsIndirect(events: List[String], jsFunc: Call, func: String => JsCmd,
    ajaxContext: AjaxContext = AjaxContext.js(Empty, Empty)): NodeSeq => NodeSeq = {
    onEvents(events, S.fmapFunc(func)(name => deferCall(Str(name + "=true"), jsFunc, ajaxContext)))
  }
}