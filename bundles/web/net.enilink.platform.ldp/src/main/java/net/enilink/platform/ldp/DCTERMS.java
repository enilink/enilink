package net.enilink.platform.ldp;

import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;

public interface DCTERMS {
    URI DCTERMS = URIs.createURI("http://purl.org/dc/terms");
    URI DCTERMS_PROPERTY_CREATED = DCTERMS.appendSegment("created");
    URI DCTERMS_PROPERTY_MODIFIED = DCTERMS.appendSegment("modified");
    URI DCTERMS_PROPERTY_FORMAT = DCTERMS.appendSegment("format");
    URI DCTERMS_PROPERTY_IDENTIFIER = DCTERMS.appendSegment("identifier");
    URI DCTERMS_PROPERTY_TITLE = DCTERMS.appendSegment("title");
}
