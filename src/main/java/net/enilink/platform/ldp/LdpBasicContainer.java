package net.enilink.platform.ldp;

import net.enilink.composition.annotations.Iri;

/**
 * LDP Basic Container (LDP-BC)
 * <p>
 * "An LDPC that defines a simple link to its contained documents [...]"
 * <p>
 * "[...] no distinction between member resources and contained resources"
 * 
 * @see https://www.w3.org/TR/ldp/#h-terms
 */
@Iri("http://www.w3.org/ns/ldp#BasicContainer")
public interface LdpBasicContainer extends LdpContainer {
}
