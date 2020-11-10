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
 *  @see http://www.w3.org/TR/ldp/
 */
public interface LDP {
	public static final String NAMESPACE = "http://www.w3.org/ns/ldp#";
	public static final URI NAMESPACE_URI = URIs.createURI(NAMESPACE);

	public static final URI TYPE_RESOURCE = NAMESPACE_URI.appendFragment("Resource");

	public static final URI TYPE_RDFSOURCE = NAMESPACE_URI.appendFragment("RDFSource");

	public static final URI TYPE_NONRDFSOURCE = NAMESPACE_URI.appendFragment("NonRDFSource");

	public static final URI TYPE_CONTAINER = NAMESPACE_URI.appendFragment("Container");

	public static final URI TYPE_BASICCONTAINER = NAMESPACE_URI.appendFragment("BasicContainer");

	public static final URI TYPE_DIRECTCONTAINER = NAMESPACE_URI.appendFragment("DirectContainer");

	public static final URI TYPE_INDIRECTCCONTAINER = NAMESPACE_URI.appendFragment("IndirectContainer");

	public static final URI TYPE_MEMBERSUBJECT = NAMESPACE_URI.appendFragment("MemberSubject");

	public static final URI PROPERTY_CONTAINS = NAMESPACE_URI.appendFragment("contains");

	public static final URI PROPERTY_MEMBER = NAMESPACE_URI.appendFragment("member");

	public static final URI PROPERTY_MEMBERSHIPRESOURCE = NAMESPACE_URI.appendFragment("membershipResource");

	public static final URI PROPERTY_HASMEMBERRELATION = NAMESPACE_URI.appendFragment("hasMemberRelation");

	public static final URI PROPERTY_ISMEMBEROFRELATION = NAMESPACE_URI.appendFragment("isMemberOfRelation");

	public static final URI PROPERTY_INSERTEDCONTENTRELATION = NAMESPACE_URI.appendFragment("insertedContentRelation");

	public static final URI PREFERENCE_CONTAINMENT = NAMESPACE_URI.appendFragment("PreferContainment");

	public static final URI PREFERENCE_MEMBERSHIP = NAMESPACE_URI.appendFragment("PreferMembership");

	public static final URI PREFERENCE_MINIMALCONTAINER = NAMESPACE_URI.appendFragment("PreferMinimalContainer");
}
