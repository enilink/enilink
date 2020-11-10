package net.enilink.platform.ldp;

import net.enilink.composition.annotations.Iri;
import net.enilink.vocab.rdfs.Resource;

/**
 * LDP Resource (LDPR)
 * <p>
 * "A HTTP resource whose state is represented in any way that conforms to the
 * simple lifecycle patterns and conventions in section 4. Linked Data Platform
 * Resources."
 * 
 * @see https://www.w3.org/TR/ldp/#h-terms
 * @see https://www.w3.org/TR/ldp/#ldpr
 */

@Iri("http://www.w3.org/ns/ldp#Resource")
public interface LdpResource extends Resource {
}
