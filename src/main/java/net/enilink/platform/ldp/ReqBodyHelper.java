package net.enilink.platform.ldp;

import net.enilink.komma.core.IReference;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.rdf4j.RDF4JValueConverter;
import net.enilink.vocab.rdf.RDF;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import java.util.HashSet;
import java.util.Set;

public class ReqBodyHelper {
    private Model m;
    private URI resourceUri;
    private static RDF4JValueConverter valueConverter;
    private static Set<URI> systemProperties;
    //  private scala.Array<Byte> binData;

    static {
        systemProperties = new HashSet<URI>() {{
            URI DCTERMS = URIs.createURI("http://purl.org/dc/terms");
            add(DCTERMS.appendSegment("created"));
            add(DCTERMS.appendSegment("modified"));
        }};
    }

    public ReqBodyHelper(Model m, URI resourceUri) {
        this.m = m;
        this.resourceUri = resourceUri;
        if (valueConverter == null)
            valueConverter = new RDF4JValueConverter(SimpleValueFactory.getInstance());
    }

//    public ReqBodyHelper(Model m, URI resourceUri){
//        this(m, resourceUri, null);
//    }
//
//    public ReqBodyHelper(scala.Array<Byte> binData, URI resourceUri) {
//        this(null, resourceUri, binData);
//    }

//    public boolean isRDF(){ return m != null &&  binData == null ; }
//
//    public boolean isNoneRDF() { return binData != null && m == null ;}

    public boolean isResource() {
        return m.contains(
                valueConverter.toRdf4j(resourceUri),
                valueConverter.toRdf4j(RDF.PROPERTY_TYPE),
                valueConverter.toRdf4j(LDP.TYPE_RESOURCE));
    }

    public boolean isRdfResource() {
        return m.contains(
                valueConverter.toRdf4j(resourceUri),
                valueConverter.toRdf4j(RDF.PROPERTY_TYPE),
                valueConverter.toRdf4j(LDP.TYPE_RDFSOURCE));
    }

    public boolean isContainer() {
        return isRdfResource() && m.contains(
                valueConverter.toRdf4j(resourceUri),
                valueConverter.toRdf4j(RDF.PROPERTY_TYPE),
                valueConverter.toRdf4j(LDP.TYPE_CONTAINER)) && isNoContains();
    }

    public boolean isBasicContainer() {
        return isRdfResource() && m.contains(
                valueConverter.toRdf4j(resourceUri),
                valueConverter.toRdf4j(RDF.PROPERTY_TYPE),
                valueConverter.toRdf4j(LDP.TYPE_BASICCONTAINER)) && isNoContains();
    }

    public boolean isDirectContainer() {
        return isRdfResource() && m.contains(
                valueConverter.toRdf4j(resourceUri), valueConverter.toRdf4j(RDF.PROPERTY_TYPE), valueConverter.toRdf4j(LDP.TYPE_DIRECTCONTAINER)) && hasReletionship() &&
                (hasRelationshipResource() || isMembership()) && isNoContains();
    }

    public boolean hasRelationshipResource() {
        return !m.filter(
                valueConverter.toRdf4j(resourceUri),
                valueConverter.toRdf4j(LDP.PROPERTY_MEMBERSHIPRESOURCE), null).isEmpty();
    }

    public boolean isNoContains() {
        return m.filter(valueConverter.toRdf4j(resourceUri), valueConverter.toRdf4j(LDP.PROPERTY_CONTAINS), null).isEmpty();
    }

    public boolean hasReletionship() {
        return !m.filter(
                valueConverter.toRdf4j(resourceUri),
                valueConverter.toRdf4j(LDP.PROPERTY_HASMEMBERRELATION), null).isEmpty();
    }

    public boolean isMembership() {
        return !m.filter(
                valueConverter.toRdf4j(resourceUri),
                valueConverter.toRdf4j(LDP.PROPERTY_ISMEMBEROFRELATION), null).isEmpty();
    }

    public boolean isServerProperty(IReference prop) {
        return systemProperties.contains(prop);
    }

    public URI getURI() {
        return resourceUri;
    }

    public Model getRdfBody() {
        return m;
    }

    public RDF4JValueConverter valueConverter() {
        return valueConverter;
    }

//     public scala.Array<Byte> binaryContent(){
//        return isNoneRDF() ? binData : null;
//     }

}

