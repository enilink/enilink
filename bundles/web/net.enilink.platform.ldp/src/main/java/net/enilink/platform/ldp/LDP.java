/*******************************************************************************
 * Copyright (c) 2015 Fraunhofer IWU and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Fraunhofer IWU - initial API and implementation
 *******************************************************************************/
package net.enilink.platform.ldp;

import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;

/**
 * Linked Data Platform: namespace, type and property constants
 *
 * @see http://www.w3.org/TR/ldp/
 */
public interface LDP {
	String NAMESPACE = "http://www.w3.org/ns/ldp#";
	URI NAMESPACE_URI = URIs.createURI(NAMESPACE);

	URI TYPE_RESOURCE = NAMESPACE_URI.appendFragment("Resource");

	URI TYPE_RDFSOURCE = NAMESPACE_URI.appendFragment("RDFSource");

	URI TYPE_NONRDFSOURCE = NAMESPACE_URI.appendFragment("NonRDFSource");

	URI TYPE_CONTAINER = NAMESPACE_URI.appendFragment("Container");

	URI TYPE_BASICCONTAINER = NAMESPACE_URI.appendFragment("BasicContainer");

	URI TYPE_DIRECTCONTAINER = NAMESPACE_URI.appendFragment("DirectContainer");

	URI TYPE_INDIRECTCCONTAINER = NAMESPACE_URI.appendFragment("IndirectContainer");

	URI TYPE_MEMBERSUBJECT = NAMESPACE_URI.appendFragment("MemberSubject");

	URI PROPERTY_CONSTRAINED_BY = NAMESPACE_URI.appendFragment("constrainedBy");

	URI PROPERTY_CONTAINS = NAMESPACE_URI.appendFragment("contains");

	URI PROPERTY_MEMBER = NAMESPACE_URI.appendFragment("member");

	URI PROPERTY_MEMBERSHIPRESOURCE = NAMESPACE_URI.appendFragment("membershipResource");

	URI PROPERTY_HASMEMBERRELATION = NAMESPACE_URI.appendFragment("hasMemberRelation");

	URI PROPERTY_ISMEMBEROFRELATION = NAMESPACE_URI.appendFragment("isMemberOfRelation");

	URI PROPERTY_INSERTEDCONTENTRELATION = NAMESPACE_URI.appendFragment("insertedContentRelation");

	URI PREFERENCE_CONTAINMENT = NAMESPACE_URI.appendFragment("PreferContainment");

	URI PREFERENCE_MEMBERSHIP = NAMESPACE_URI.appendFragment("PreferMembership");

	URI PREFERENCE_MINIMALCONTAINER = NAMESPACE_URI.appendFragment("PreferMinimalContainer");

	URI DCTERMS = URIs.createURI("http://purl.org/dc/terms");

	URI DCTERMS_PROPERTY_CREATED = DCTERMS.appendSegment("created");

	URI DCTERMS_PROPERTY_MODIFIED = DCTERMS.appendSegment("modified");

}
