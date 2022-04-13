package net.enilink.platform.ldp;

import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;

public interface DCTERMS {
    public static final URI DCTERMS = URIs.createURI("http://purl.org/dc/terms");
    public static final URI DCTERMS_PROPERTY_CREATED = DCTERMS.appendSegment("created");
    public static final URI DCTERMS_PROPERTY_MODIFIED = DCTERMS.appendSegment("modified");
    public static final URI DCTERMS_PROPERTY_FORMAT = DCTERMS.appendSegment("format");
    public static final URI DCTERMS_PROPERTY_IDENTIFIER = DCTERMS.appendSegment("identifier");
    public static final URI DCTERMS_PROPERTY_TITLE = DCTERMS.appendSegment("title");
}
