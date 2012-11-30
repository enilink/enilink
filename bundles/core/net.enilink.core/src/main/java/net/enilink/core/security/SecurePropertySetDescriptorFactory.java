package net.enilink.core.security;

import java.beans.PropertyDescriptor;
import java.util.List;

import net.enilink.vocab.acl.ACL;

import net.enilink.composition.properties.PropertySet;
import net.enilink.composition.properties.PropertySetDescriptor;
import net.enilink.composition.properties.komma.KommaPropertySet;
import net.enilink.composition.properties.komma.KommaPropertySetDescriptor;
import net.enilink.composition.properties.komma.KommaPropertySetFactory;

import net.enilink.komma.model.MODELS;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.URI;

/**
 * A Factory that can be used to create secure property sets. These property
 * sets consider ACLs when retrieving property values.
 */
public class SecurePropertySetDescriptorFactory extends
		KommaPropertySetFactory {
	@Override
	public <E> PropertySetDescriptor<E> createDescriptor(
			PropertyDescriptor property, String uri, boolean readOnly) {
		return new KommaPropertySetDescriptor<E>(property, uri) {
			@SuppressWarnings("unchecked")
			@Override
			public PropertySet<E> createPropertySet(Object bean) {
				if (bean instanceof ISecureModelSet
						&& predicate.equals(MODELS.PROPERTY_MODEL)) {
					return new KommaPropertySet<E>((IReference) bean,
							predicate, (Class<E>) type, rdfValueType) {
						@Override
						protected IQuery<?> createElementsQuery(String query,
								String filterPattern, int limit) {
							URI userId = SecurityUtil.getUser();
							if (userId != null) {
								query = "prefix acl: <"
										+ ACL.NAMESPACE
										+ "> "
										+ "SELECT DISTINCT ?o WHERE { ?s ?p ?o . "
										+ "{ ?target acl:owner ?agent } union {" //
										+ "{ ?acl acl:accessTo ?target } union { ?acl acl:accessToClass ?class . ?target a ?class } . ?acl acl:mode ?mode . "
										+ "{ ?acl acl:agent ?agent } union { ?agent a ?agentClass . ?acl acl:agentClass ?agentClass }"
										+ "}}";
								return super
										.createElementsQuery(query,
												filterPattern, limit)
										.setParameter("agent", userId)
										.setParameter("mode", ACL.TYPE_READ);
							}
							return super.createElementsQuery(query,
									filterPattern, limit);
						}

						@Override
						protected void setCache(List<E> cache) {
							// do not cache the values
						}
					};
				}
				return super.createPropertySet(bean);
			}
		};
	}
}
