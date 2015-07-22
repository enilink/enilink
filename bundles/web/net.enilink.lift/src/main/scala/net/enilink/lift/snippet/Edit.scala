package net.enilink.lift.snippet

import java.io.ByteArrayInputStream
import java.util.UUID
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.LinkedHashSet
import scala.collection.mutable.ListBuffer
import scala.xml.NodeSeq
import org.eclipse.core.runtime.IStatus
import net.enilink.komma.common.command.AbortExecutionException
import net.enilink.komma.common.command.CommandResult
import net.enilink.komma.common.command.ICommand
import net.enilink.komma.core.BlankNode
import net.enilink.komma.core.IEntityManager
import net.enilink.komma.core.IReference
import net.enilink.komma.core.IStatement
import net.enilink.komma.core.IStatementPattern
import net.enilink.komma.core.Statement
import net.enilink.komma.core.URI
import net.enilink.komma.core.URIs
import net.enilink.komma.core.visitor.IDataVisitor
import net.enilink.komma.edit.domain.IEditingDomainProvider
import net.enilink.komma.edit.properties.EditingHelper
import net.enilink.komma.edit.util.PropertyUtil
import net.enilink.komma.em.concepts.IProperty
import net.enilink.komma.em.concepts.IResource
import net.enilink.komma.model.IModel
import net.enilink.komma.model.ModelUtil
import net.enilink.lift.rdfa.RDFaParser
import net.enilink.lift.util.AjaxHelpers
import net.enilink.lift.util.Globals
import net.enilink.lift.util.CurrentContext
import net.enilink.lift.util.TemplateHelpers
import net.liftweb.common.Box
import net.liftweb.http.DispatchSnippet
import net.liftweb.http.S
import net.liftweb.http.js.JE._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.JsExp.strToJsExp
import net.liftweb.json._
import net.liftweb.util.JsonCommand
import net.liftweb.util.LiftFlowOfControlException
import net.liftweb.common.Full
import net.enilink.komma.edit.properties.IResourceProposal
import net.liftweb.common.Empty
import scala.xml.Group
import net.enilink.komma.core.IEntity
import org.eclipse.core.runtime.Status

case class ProposeInput(rdf: JValue, query: String, index: Option[Int])
case class GetValueInput(rdf: JValue)
case class SetValueInput(rdf: JValue, value: JValue, template: Option[String], what: Option[String])

class JsonCallHandler {
  implicit val formats = DefaultFormats

  val (call, jsCmd) = AjaxHelpers.createJsonFunc(this.apply)

  def model = Globals.contextModel.vend

  val path = S.request map (_.path)

  class RdfEditingHelper(editType: EditingHelper.Type) extends EditingHelper(editType) {
    override def getEditingDomain = model.map(_.getModelSet.adapters.getAdapter(classOf[IEditingDomainProvider]) match {
      case p: IEditingDomainProvider => p.getEditingDomain
      case _ => null
    }) openOr null

    override def getEditingSupport(element: Object) = {
      super.getEditingSupport(element)
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

  def createHelper(editProperty: Boolean = false) = new RdfEditingHelper(if (editProperty) EditingHelper.Type.PROPERTY else EditingHelper.Type.VALUE)

  def resolve(stmt: IStatement): Option[IStatement] = stmt match {
    case stmt: IStatement => model.map { m =>
      val em = m.getManager
      val p = stmt.getPredicate
      new Statement(em.find(stmt.getSubject), if (p == null) null else em.find(p), stmt.getObject)
    }
    case _ => None
  }

  def removeResources(resources: List[String], gc: Boolean = false) = {
    (for (model <- model; em = model.getManager; transaction = em.getTransaction) yield {
      transaction.begin
      try {
        resources.foreach { resource =>
          val ref = if (resource.startsWith("_:")) em.createReference(resource)
          else if (resource.startsWith("<") && resource.endsWith(">")) URIs.createURI(resource.substring(1, resource.length - 1))
          else URIs.createURI(resource)
          if (!(gc && em.hasMatchAsserted(null, null, ref))) {
            em.removeRecursive(ref, true)
          }
        }
        transaction.commit
        JBool(true)
      } catch {
        case _: Exception => if (transaction.isActive) transaction.rollback; JBool(false)
      }
    }) openOr JBool(false)
  }

  def apply: PartialFunction[JValue, Any] = {
    case JsonCommand("removeResource", _, JArray(resources)) => removeResources(resources.map(_.values.toString))
    case JsonCommand("removeResource", _, JString(resource)) => removeResources(List(resource))

    case JsonCommand("gcResource", _, JArray(resources)) => removeResources(resources.map(_.values.toString), true)
    case JsonCommand("gcResource", _, JString(resource)) => removeResources(List(resource), true)

    case JsonCommand("blankNode", _, _) => {
      ((for (model <- model; em = model.getManager) yield JString(em.createReference.toString)) openOr JString(new BlankNode().toString))
    }
    case JsonCommand("namespace", _, prefix) => (prefix match {
      case JString(prefix) => Full(prefix)
      case JNull => Full("")
      case _ => Empty
    }).flatMap { p => model.flatMap(m => Box.legacyNullTest(m.getManager.getNamespace(p))) }.map(ns => JString(ns.toString)) openOr JNull
    case JsonCommand("namespaces", _, _) => JObject(for {
      m <- model.toList; ns <- m.getManager.getNamespaces.iterator
    } yield JField(ns.getPrefix, JString(ns.getURI.toString)))
    case JsonCommand("updateTriples", _, params) => {
      import scala.collection.JavaConversions._
      var success = false
      var replacements: mutable.Map[String, IReference] = null
      for (
        model <- model ?~ "No active model found"
      ) {
        val em = model.getManager
        try {
          em.getTransaction.begin
          (params \ "add") match {
            case JNothing =>
            case add: JValue => {
              replacements = new mutable.HashMap[String, IReference]
              val stmts = renameNewNodes(statements(add), em, model.getURI, replacements)
              em.add(stmts)
            }
          }
          // TODO recursive removal of BNodes
          (params \ "remove") match {
            case JNothing =>
            case remove: JValue => em.remove(statements(remove): java.lang.Iterable[IStatementPattern])
          }
          em.getTransaction.commit
          S.notice("Update was sucessful.")
          success = true
        } catch {
          case e: Exception => if (em.getTransaction.isActive) em.getTransaction.rollback
        }
      }
      if (success) {
        import net.liftweb.json.Extraction._
        if (replacements != null) decompose(replacements.map { case (k, v) => (k, v.toString) }.toMap) else JBool(true)
      } else JBool(false)
    }
    case JsonCommand("propose", _, params) => {
      import net.liftweb.json.JsonDSL._
      val proposals = for (
        ProposeInput(rdf, query, index) <- params.extractOpt[ProposeInput];
        stmt <- statements(rdf).headOption.flatMap(resolve _);
        proposalSupport <- Option(createHelper(stmt.getPredicate == null).getProposalSupport(stmt));
        proposalProvider <- Option(proposalSupport.getProposalProvider)
      ) yield {
        proposalProvider.getProposals(query, index getOrElse query.length).map { p =>
          val o = ("label", p.getLabel) ~ ("content", p.getContent) ~ ("description", p.getDescription) ~
            ("cursorPosition", p.getCursorPosition) ~ ("insert", p.isInsert)
          p match {
            case resProposal: IResourceProposal =>
              val o2 = if (resProposal.getUseAsValue) o ~ ("resource", resProposal.getResource.getReference.toString) else o
              o2 ~ ("perfectMatch", resProposal.getScore() >= 1000)
            case other => o
          }
        }.toList
      }
      proposals map (JArray(_)) getOrElse JArray(Nil)
    }
    case JsonCommand("getValue", _, params) => {
      params.extractOpt[GetValueInput] flatMap {
        case GetValueInput(rdf) => statements(rdf) match {
          case stmt :: _ => resolve(stmt).map(createHelper().getValue(_))
          case _ => None
        }
        case _ => None
      } map (v => JString(v.toString)) getOrElse JString("")
    }
    case JsonCommand("removeValue", _, rdf) => {
      import scala.collection.JavaConversions._
      var successful = false
      for (
        model <- model ?~ "No active model found"
      ) {
        val em = model.getManager
        val helper = createHelper()
        val editingDomain = helper.getEditingDomain
        try {
          em.getTransaction.begin
          statements(rdf) match {
            case stmt :: _ => {
              val removeCommand = PropertyUtil.getRemoveCommand(
                editingDomain,
                em.find(stmt.getSubject, classOf[IResource]),
                em.find(stmt.getPredicate, classOf[IProperty]),
                stmt.getObject);
              successful &= helper.execute(removeCommand).getStatus.isOK
            }
          }
          em.getTransaction.commit
          successful = true
        } catch {
          case e: Exception => if (em.getTransaction.isActive) em.getTransaction.rollback
        }
      }
      JBool(successful)
    }
    case JsonCommand("setValue", _, params) => {
      import scala.collection.JavaConversions._
      import net.enilink.lift.util.TemplateHelpers._
      import net.liftweb.util.Helpers._

      lazy val okResult = JObject(Nil)
      params.extractOpt[SetValueInput] map {
        case SetValueInput(rdf, value, template, templatePath) =>
          statements(rdf) match {
            case stmt :: _ => {
              val cmdResult = resolve(stmt).map { s =>
                createHelper().setValue(s, s.getSubject.asInstanceOf[IEntity].getEntityManager, value match {
                  case JString(s) => s.trim
                  // also allow RDF/JSON encoded values
                  case _ => valueFromJSON(value)
                })
              }
              val status = cmdResult.map(_.getStatus) getOrElse Status.CANCEL_STATUS
              if (status.isOK) {
                template match {
                  case Some(tname) =>
                    val result = for {
                      p <- templatePath.filter(_.nonEmpty).map(_.stripPrefix("/").split("/").toList) orElse path.map(_.wholePath)
                      template <- TemplateHelpers.find(p, Full(tname))
                    } yield {
                      import net.enilink.lift.rdf._
                      // println("Template: " + template)
                      // TODO check if template already got an rdfa root snippet
                      val wrappedTemplate = <div about="?this" data-lift="rdfa">{ template }</div>
                      val resultValue = cmdResult.flatMap(_.getReturnValues.headOption)
                      val vars = new LinkedHashSet[Variable]()
                      var params = new RDFaParser {
                        override def createVariable(name: String) = {
                          val v = Variable(name.substring(1), None)
                          vars.add(v)
                          Some(v)
                        }
                        override def transformLiteral(e: xml.Elem, content: NodeSeq, literal: Literal): (xml.Elem, Node) = {
                          super.transformLiteral(e, content, literal) match {
                            case (e1, PlainLiteral(variable(l), _)) => (e1, createVariable(l).get)
                            case other => other
                          }
                        }
                      }.getArcs(wrappedTemplate, S.request.map(r => r.hostAndPath + r.uri) openOr "http://unknown/").flatMap {
                        // TODO support reverse relationships
                        case (Variable("this", _), rel, objVar: Variable) => (
                          rel match {
                            case v: Variable => List((v.sym.name, stmt.getPredicate))
                            case _ => Nil
                          }) ++ resultValue.flatMap(v => Some((objVar.sym.name, v)))
                        case _ => Nil
                      }.toMap
                      // [rel] or [rev] was not contained in current HTML fragment
                      // simply bind first var that is different from ?this
                      if (params.isEmpty) params = resultValue flatMap { value =>
                        vars.collectFirst { case v if v.n != "this" => v } map { v => (v.sym.name, value) }
                      } toMap
                      val renderResult = model flatMap { m =>
                        Globals.contextModel.doWith(Full(m)) {
                          CurrentContext.withSubject(m.getManager.find(stmt.getSubject)) {
                            QueryParams.doWith(params) { TemplateHelpers.withAppFor(p)(TemplateHelpers.render(wrappedTemplate)) }
                          }
                        }
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

  /**
   * Parses RDF JSON Alternate Serialization (RDF/JSON) in the form { "S" : { "P" : [ O ] } }.
   */
  def statementsFromJSON(rdf: JObject): Seq[IStatement] = {
    def toResource(ref: String) = if (ref.startsWith("_:")) new BlankNode(ref) else URIs.createURI(ref)
    rdf.children flatMap {
      case JField(s, po: JObject) =>
        val sResource = toResource(s)
        po.children flatMap {
          case JField(p, JArray(objs)) => objs map { o =>
            val pResource = toResource(p)
            val stmt = new Statement(sResource, pResource, valueFromJSON(o))
            stmt
          }
          case _ => Nil
        }
      case _ => Nil
    }
  }

  def valueFromJSON(o: JValue) = {
    import net.enilink.komma.core.Literal
    val oValue = (o \ "value").values.toString
    (o \ "type") match {
      case JString("uri") => URIs.createURI(oValue)
      case JString("bnode") => new BlankNode(oValue)
      case _ /* JString("literal") */ => (o \ "datatype") match {
        case JString(datatype) => new Literal(oValue, URIs.createURI(datatype))
        case _ => (o \ "lang") match {
          case JString(lang) => new Literal(oValue, lang)
          case _ => new Literal(oValue)
        }
      }
    }
  }

  val NULL_URI = URIs.createURI("komma:null")

  /**
   * Parses RDF/JSON, RDF/XML and Turtle.
   */
  def statements(rdf: JValue): Seq[IStatement] = {
    def unwrapNull[T >: Null](v: T): T = if (v == NULL_URI) null else v

    (rdf match {
      case rdfJson: JObject => statementsFromJSON(rdfJson)
      case JString(rdfStr) =>
        val stmts = new ListBuffer[IStatement]
        ModelUtil.readData(new ByteArrayInputStream(rdfStr.getBytes("UTF-8")), "", null, true, new IDataVisitor[Unit]() {
          override def visitBegin {}
          override def visitEnd {}
          override def visitStatement(stmt: IStatement) = stmts += stmt
        })
        stmts.toSeq
      case _ => Nil
    }) map {
      stmt => new Statement(unwrapNull(stmt.getSubject), unwrapNull(stmt.getPredicate), unwrapNull(stmt.getObject))
    }
  }

  /** Replace blank nodes with prefix "new" by new blank nodes generated by an entity manager. */
  def renameNewNodes(stmts: Seq[IStatement], em: IEntityManager, baseURI: URI, replacements: mutable.Map[String, IReference] = new mutable.HashMap) = {
    def replace[T](node: T): T = (node match {
      case ref: IReference if ref.getURI == null && ref.toString.startsWith("_:new-") => replacements.getOrElseUpdate(ref.toString, em.createReference)
      case ref: IReference if ref.getURI != null && "new".equals(ref.getURI.scheme) => replacements.getOrElseUpdate(ref.toString, baseURI.appendLocalPart(ref.getURI.opaquePart + UUID.randomUUID))
      case other => other
    }).asInstanceOf[T]
    stmts map { stmt =>
      val (s, p, o) = (stmt.getSubject, stmt.getPredicate, stmt.getObject)
      val (s2, p2, o2) = (replace(s), replace(p), replace(o))
      if ((s ne s2) || (p ne p2) || (o ne o2)) new Statement(s2, p2, o2, stmt.getContext) else stmt
    }
  }
}

class Edit extends DispatchSnippet {
  val dispatch = Map("render" -> buildFuncs _)

  def modelParam = JsRaw("model !== undefined ? { model : model } : undefined")

  def buildFuncs(in: NodeSeq): NodeSeq = {
    val handler = new JsonCallHandler
    Script(handler.jsCmd &
      SetExp(JsVar("enilink"), JsRaw("window.enilink || {}")) & //
      SetExp(JsVar("enilink.rdf"), Call("$.extend", JsRaw("window.enilink.rdf || {}"),
        JsObj(
          ("blankNode", AnonFunc("callback, model", handler.call("blankNode", JsRaw("{}"), JsVar("callback"), modelParam))), //
          ("namespace", AnonFunc("prefix, callback, model", handler.call("namespace", JsVar("prefix"), JsVar("callback"), modelParam))), //
          ("namespaces", AnonFunc("callback, model", handler.call("namespaces", JsRaw("null"), JsVar("callback"), modelParam))), //
          ("removeResource", AnonFunc("resource, callback, model", handler.call("removeResource", JsVar("resource"), JsVar("callback"), modelParam))), //
          ("gcResource", AnonFunc("resource, callback, model", handler.call("gcResource", JsVar("resource"), JsVar("callback"), modelParam))), //
          ("updateTriples", AnonFunc("add, remove, callback, model",
            JsRaw("""var params;
if (add && (add.add !== undefined || add.remove !== undefined)) {
	params = add;	model = callback;	callback = remove;
} else {
	if (typeof remove == "function") {
		model = callback;
		callback = remove;
		remove = undefined;
	}
	params = { 'add' : add, 'remove' : remove };
}
""") & handler.call("updateTriples", JsRaw("params"), JsRaw("callback"), modelParam))), //
          ("getValue", AnonFunc("rdf, callback, model", handler.call("getValue", JsRaw("{ 'rdf' : rdf }"), JsVar("callback"), modelParam))), //
          ("setValue", AnonFunc("data, callback, model", handler.call("setValue", JsVar("data"), JsVar("callback"), modelParam))), //
          ("removeValue", AnonFunc("rdf, callback, model", handler.call("removeValue", JsVar("rdf"), JsVar("callback"), modelParam))), //
          ("propose", AnonFunc("data, callback, model", handler.call("propose", JsVar("data"), JsVar("callback"), modelParam)) //
          )))))
  }
}