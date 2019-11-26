package net.enilink.ldp;

import net.enilink.composition.annotations.Iri;
import net.enilink.vocab.rdf.Property;

/**
 * LDP Direct Container (LDP-DC)
 * <p>
 * "An LDPC that adds the concept of membership [...]"
 * 
 * @see https://www.w3.org/TR/ldp/#h-terms
 */
@Iri("http://www.w3.org/ns/ldp#DirectContainer")
public interface LdpDirectContainer extends LdpContainer {

	/**
	 * Denote the LDPC's membership-constant-URI
	 * 
	 * @see https://www.w3.org/TR/ldp/#ldpdc section 5.4.1.3
	 */
	@Iri("http://www.w3.org/ns/ldp#membershipResource")
	LdpResource membershipResource();

	void membershipResource(LdpResource ldpr);

	/**
	 * Membership predicate used to express which member resources this container
	 * has, results in the membership triple pattern:
	 * <p>
	 * (membership-constant-URI, ${membership-predicate}, member-derived-URI)
	 * <p>
	 * <b>ATTENTION:</b> <em>EITHER</em> this <em>OR</em> isMemberOfRelation can be
	 * used.
	 * 
	 * @see https://www.w3.org/TR/ldp/#ldpdc section 5.4.1.4.1
	 */
	@Iri("http://www.w3.org/ns/ldp#hasMemberRelation")
	Property hasMemberRelation();

	void hasMemberRelation(Property hasMemberRelation);

	/**
	 * Membership predicate used to express which resources are members of this
	 * container, results in the membership triple pattern:
	 * <p>
	 * (membership-derived-URI, ${membership-predicate}, member-constant-URI)
	 * <p>
	 * <b>ATTENTION:</b> <em>EITHER</em> this <em>OR</em> hasMemberRelation can be
	 * used.
	 *
	 * @see https://www.w3.org/TR/ldp/#ldpdc section 5.4.1.4.2
	 */
	@Iri("http://www.w3.org/ns/ldp#isMemberOfRelation")
	Property isMemberOfRelation();

	void isMemberOfRelation(Property isMemberOfRelation);
}
