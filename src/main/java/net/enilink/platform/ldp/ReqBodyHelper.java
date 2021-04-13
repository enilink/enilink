package net.enilink.platform.ldp;

import net.enilink.komma.core.IReference;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.rdf4j.RDF4JValueConverter;
import net.enilink.vocab.rdf.RDF;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

import java.util.*;
import java.util.stream.Collectors;

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

    public static Map<Integer,String> preference(String preferenceHeader) {
        if (null != preferenceHeader) {
            List<String> prefs = Arrays.stream(preferenceHeader.split(";")).map(s -> s.trim()).collect(Collectors.toList());
            if (null != prefs && prefs.size() == 2) {
                Map<String, Integer> uriToPrefs = new HashMap<String, Integer>() {{
                    put(LDP.PREFERENCE_MINIMALCONTAINER.toString(), PreferenceHelper.MINIMAL_CONTAINER);
                    put(LDP.PREFERENCE_CONTAINMENT.toString(), PreferenceHelper.INCLUDE_CONTAINMENT);
                    put(LDP.PREFERENCE_MEMBERSHIP.toString(), PreferenceHelper.INCLUDE_MEMBERSHIP);
                }};
                List<String> action = Arrays.stream(prefs.get(1).split("=")).map(s -> s.trim()).collect(Collectors.toList());
                if (null != action && action.size() == 2) {
                    List<String> requests = Arrays.stream(action.get(1).split(" ")).map(s -> s.trim()).collect(Collectors.toList());
                    if ("include".equals(action.get(0))) {
                        int acc = 0;
                        for (String p : requests) {
                            Integer val = uriToPrefs.get(p.replace("\"", ""));
                            if (null == val) {
                                acc = PreferenceHelper.defaultPreferences();
                                break;
                            }
                            acc = acc | uriToPrefs.get(p.replace("\"", ""));
                        }
                        if (acc > 0) return Collections.singletonMap(acc, prefs.get(0));
                        return Collections.singletonMap(PreferenceHelper.defaultPreferences(), prefs.get(0));
                    } else if ("omit".equals(action.get(0))) {
                        int acc = PreferenceHelper.defaultPreferences();
                        for (String p : requests) acc = acc - uriToPrefs.get(p.replace("\"", ""));
                        if (acc != 0) return Collections.singletonMap(acc, prefs.get(0));
                        return Collections.singletonMap(PreferenceHelper.MINIMAL_CONTAINER, prefs.get(0));
                    }
                    return Collections.singletonMap(PreferenceHelper.defaultPreferences(), prefs.get(0));
                }
            }
        }
        return Collections.singletonMap(PreferenceHelper.defaultPreferences(), "");
    }

    public static URI  resourceType(String linkHeader) {
        if (linkHeader == null || linkHeader.isEmpty())
            return LDP.TYPE_RDFSOURCE;
        else{
            String[] header = linkHeader.split(";");
            String type = null;
            if(header != null && header.length > 1)
                type = header[0].substring(1, header[0].length() - 1);
            return type !=null ? URIs.createURI(type, true) :  LDP.TYPE_RDFSOURCE;
        }
    }

}

