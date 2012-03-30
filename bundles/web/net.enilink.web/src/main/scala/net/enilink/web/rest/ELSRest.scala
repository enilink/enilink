package net.enilink.web.rest

import scala.collection.JavaConversions._
import net.enilink.komma.concepts.IResource
import net.enilink.komma.model.IModel
import net.enilink.komma.core.IReference
import net.enilink.komma.core.URIImpl
import net.liftweb.http.rest.RestHelper
import net.liftweb.http.LiftResponse
import net.liftweb.http.Req
import net.enilink.lift.util.Globals
import net.enilink.komma.model.ModelUtil
import net.liftweb.common.Box
import net.liftweb.common.Failure

object ELSRest extends RestHelper {
  implicit def referenceToString(ref: IReference) = if (ref != null) ref.toString else ""
  implicit def stringToUri(uriStr: String) = URIImpl.createURI(uriStr)

  override def defaultGetAsXml = true

  serve {
    case "services" :: "els" :: nodeName :: _ XmlGet _ => {
      Globals.contextModel.vend match {
        case model: AnyRef => {
          val manager = model.getManager
          val node = manager.find(nodeName, classOf[IResource])

          <NodeData ID={ node } ParentID={
            node.getContainer match {
              case c: AnyRef => c : String
              case _ => null
            }
          } Name={ ModelUtil.getLabel(node) }>
            <ChildList>
              {
                node.getContents.map {
                  child =>
                    <Child>
                      <ID>{ child }</ID>
                      <Name>{ ModelUtil.getLabel(child) }</Name>
                      <CharacteristicList>
                        <Characteristic>
                          <View>im Durchschnitt</View>
                          <Name>Gesamtenergieverbrauch</Name>
                          <MeasurementUnit>KWh</MeasurementUnit>
                          <Value>153.00</Value>
                        </Characteristic>
                      </CharacteristicList>
                    </Child>
                      
                }
              }
            </ChildList>
            <AssociationToOtherNode>
              <Association>
                <OtherNodeID>otherNodeID</OtherNodeID>
                <Dimension>Prozess|Anlagenstruktur</Dimension>
                <Name>Other Node</Name>
              </Association>
            </AssociationToOtherNode>
          </NodeData>
        }
        case _ => Failure("Model not found."): Box[LiftResponse]
      }
    }
  }
}