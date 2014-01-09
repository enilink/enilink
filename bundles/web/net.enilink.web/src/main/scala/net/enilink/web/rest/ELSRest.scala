package net.enilink.web.rest

import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.JavaConversions.asScalaIterator
import scala.collection.JavaConversions.asScalaSet
import net.enilink.komma.em.concepts.IClass
import net.enilink.komma.em.concepts.IResource
import net.enilink.komma.em.util.ISparqlConstants
import net.enilink.komma.model.IModel
import net.enilink.komma.model.ModelUtil
import net.enilink.komma.core.IEntity
import net.enilink.komma.core.IReference
import net.enilink.komma.core.URI
import net.enilink.komma.core.URIImpl
import net.enilink.lift.util.Globals
import net.liftweb.http.S
import net.liftweb.http.rest.RestHelper
import net.enilink.vocab.rdf.RDF
import net.enilink.vocab.rdfs.RDFS

object ELSRest extends RestHelper {
  object MFG {
    val NS_URI = URIImpl.createURI("http://enilink.net/vocab/manufacturing#")
    val REQUIRES = NS_URI.appendLocalPart("requires")
    val PROCESS = NS_URI.appendLocalPart("Process")
    val RESOURCE = NS_URI.appendLocalPart("Resource")
  }

  implicit def referenceToString(ref: IReference) = if (ref != null) ref.toString else ""
  implicit def stringToUri(uriStr: String) = URIImpl.createURI(uriStr)

  override def defaultGetAsXml = true

  def model() = Globals.contextModel.vend ?~ "Model not found."

  serve("services" / "els" prefix {
    case "processtypes" :: Nil XmlGet _ =>
      model.map(listProcessTypes(_))
    case "processes" :: Nil XmlGet _ =>
      model.map(listNodes(MFG.PROCESS, _))
    case "resources" :: Nil XmlGet _ =>
      model.map(listNodes(MFG.RESOURCE, _))
    case "node" :: Nil XmlGet _ => {
      for (
        model <- model();
        id <- S.param("id") ?~ "Node id missing."
      ) yield {
        val node = model.getManager.find(id, classOf[IResource])
        <NodeData ID={ node } ParentID={ getParent(node) } Name={ ModelUtil.getLabel(node) }>
          <ChildList>
            {
              getChildren(node) map {
                child =>
                  <Child>
                    <ID>{ child }</ID>
                    <Name>{ ModelUtil.getLabel(child) }</Name>
                    <CharacteristicList>
                      { getCharacteristics(child) }
                    </CharacteristicList>
                  </Child>
              }
            }
          </ChildList>
          <AssociationToOtherNode>
            { getAssociations(node) }
          </AssociationToOtherNode>
        </NodeData>
      }
    }
  })

  def getChildren(node: IResource): Iterator[IResource] = node match {
    // use direct, non-inferred sub classes for classes
    case clazz: IClass => clazz.getDirectNamedSubClasses
    // use contents for resources
    case node => node.getContents.iterator
  }

  def getParent(node: IResource) = node match {
    // use direct, non-inferred super classes for classes
    case clazz: IClass => clazz.getDirectNamedSuperClasses.collectFirst { case c => c: String } getOrElse null
    // use container for resources
    case node => node.getContainer match {
      case c: AnyRef => c: String
      case _ => null
    }
  }

  def writeDimensionStructure(nodeType: Any, nodes: TraversableOnce[IEntity]) = {
    <DimensionStructure>
      <Dimension>{
        nodeType match {
          case MFG.PROCESS => "Prozess"
          case MFG.RESOURCE => "Anlagenstruktur"
          case _ => "Verrichtung"
        }
      }</Dimension>
      <DimensionNodes>{
        nodes map { node =>
          <DimensionNode><ID>{ node }</ID></DimensionNode>
        }
      }</DimensionNodes>
    </DimensionStructure>
  }

  def listProcessTypes(model: IModel) = {
    val results = model.getManager.createQuery(ISparqlConstants.PREFIX + """
         prefix mfg: <""" + MFG.NS_URI + """>
         
         select distinct ?class where {
         	?class rdfs:subClassOf mfg:Process
         	filter not exists {
         		?other rdfs:subClassOf mfg:Process .
         		?class rdfs:subClassOf ?other
         		filter (?other != ?class)
         	}
         }""", false).evaluate(classOf[IClass])
    writeDimensionStructure("Verrichtung", results)
  }

  def listNodes(nodeType: URI, model: IModel) = {
    val results = model.getManager.createQuery(ISparqlConstants.PREFIX + """
        select distinct ?node where {
    		?node a ?type .
        """ +
      // filter (shared) resources without parents to ensure that only top-level resources are returned
      (if (nodeType == MFG.RESOURCE) """
        ?process ?requires ?node .
        filter not exists { ?otherProcess komma:contains ?process }
        """
      else "")
      + """
      		filter not exists { ?other a ?type; komma:contains ?node }
    	} order by ?node""")
      .setParameter("type", nodeType)
      .setParameter("requires", MFG.REQUIRES)
      .evaluate(classOf[IEntity])
    writeDimensionStructure(nodeType, results)
  }

  /**
   * List indicators (energy consumption, etc.) for given resource or process
   */
  def getCharacteristics(node: IResource) = {
    <Characteristic>
      <View>im Durchschnitt|pro Tür</View>
      <Name>Gesamtenergieverbrauch</Name>
      <MeasurementUnit>kWh</MeasurementUnit>
      <Value>{ // generate some value
        node.toString.length * 5
      }</Value>
    </Characteristic>
  }

  /**
   * List associated resources for processes and vice-versa.
   */
  def getAssociations(node: IResource) = {
    def association(dim: String, node: IEntity) = {
      <Association>
        <OtherNodeID>{ node }</OtherNodeID>
        <Dimension>{ dim }</Dimension>
        <Name>{ ModelUtil.getLabel(node) }</Name>
      </Association>
    }

    val isProcess = node.getRdfTypes.contains(MFG.PROCESS)
    val isResource = !isProcess && node.getRdfTypes.contains(MFG.RESOURCE)

    val variable = if (isResource) "process" else "resource"
    val query = node.getEntityManager.createQuery("select ?" + variable + " where { ?process ?requires ?resource } order by ?" + variable)
    query.setParameter("requires", MFG.REQUIRES)
    query.setParameter(if (isResource) "resource" else "process", node)

    if (isProcess || isResource) {
      var result = query.evaluate(classOf[IResource]).toList map { association(if (isResource) "Prozess" else "Anlagenstruktur", _) }
      // add process types for processes
      if (isProcess) result ++= node.getDirectNamedClasses.toList filter (_ != RDFS.TYPE_RESOURCE) map { association("Verrichtung", _) }
      result
    } else node match {
      // handle process types, returns a list of instances
      case clazz: IClass => clazz.getInstances.iterator map { association("Prozess", _) }
      // unknown resource
      case _ => Nil
    }
  }
}