package net.enilink.core.security;

import java.beans.PropertyDescriptor;
import java.util.List;

import net.enilink.vocab.acl.ACL;

import net.enilink.composition.properties.PropertySet;
import net.enilink.composition.properties.PropertySetDescriptor;
import net.enilink.composition.properties.komma.KommaPropertySet;
import net.enilink.composition.properties.komma.KommaPropertySetDescriptor;
import net.enilink.composition.properties.komma.KommaPropertySetDescriptorFactory;

import net.enilink.komma.model.MODELS;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.URI;

public class SecurePropertySetDescriptorFactory extends
		KommaPropertySetDescriptorFactory {
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
						protected IQuery<?> createElementsQuery() {
							URI userId = SecurityUtil.getUserId();
							if (userId != null) {
								return manager
										.createQuery(
												"prefix acl: <"
														+ ACL.NAMESPACE
														+ "> "
														+ "SELECT DISTINCT ?o WHERE { ?s ?p ?o . "
														+ "?acl acl:accessTo ?o . ?acl acl:mode ?mode . "
														+ "{ ?acl acl:agent ?agent } union { ?agent a ?agentClass . ?acl acl:agentClass ?agentClass }"
														+ " }")
										.setParameter("s", bean)
										.setParameter("p", property)
										.setParameter("agent", userId)
										.setParameter("mode", ACL.TYPE_READ);
							}
							return super.createElementsQuery();
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
