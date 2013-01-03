package net.enilink.core.security;

import java.lang.annotation.Annotation;
import java.util.List;

import net.enilink.vocab.acl.ACL;

import net.enilink.composition.properties.PropertySet;
import net.enilink.composition.properties.annotations.Type;
import net.enilink.composition.properties.komma.KommaPropertySet;
import net.enilink.composition.properties.komma.KommaPropertySetFactory;

import net.enilink.komma.model.MODELS;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIImpl;

/**
 * A Factory that can be used to create secure property sets. These property
 * sets consider ACLs when retrieving property values.
 */
public class SecurePropertySetFactory extends KommaPropertySetFactory {
	@Override
	public <E> PropertySet<E> createPropertySet(Object bean, String uri,
			Class<E> elementType, Annotation... annotations) {
		if (bean instanceof ISecureModelSet
				&& uri.equals(MODELS.PROPERTY_MODEL.toString())) {
			URI predicate = URIImpl.createURI(uri);
			URI rdfValueType = null;
			for (Annotation annotation : annotations) {
				if (Type.class.equals(annotation.annotationType())) {
					rdfValueType = URIImpl.createURI(((Type) annotation)
							.value());
				}
			}
			PropertySet<E> propertySet = new KommaPropertySet<E>(
					(IReference) bean, predicate, elementType, rdfValueType) {
				@Override
				protected IQuery<?> createElementsQuery(String query,
						String filterPattern, int limit) {
					URI userId = SecurityUtil.getUser();
					if (userId != null) {
						query = "prefix acl: <"
								+ ACL.NAMESPACE
								+ "> "
								+ "SELECT DISTINCT ?o WHERE { ?s ?p ?o . "
								+ "{ ?o acl:owner ?agent } union {" //
								+ "{ ?acl acl:accessTo ?o } union { ?acl acl:accessToClass ?class . ?o a ?class } . ?acl acl:mode ?mode . "
								+ "{ ?acl acl:agent ?agent } union { ?agent a ?agentClass . ?acl acl:agentClass ?agentClass }"
								+ "}}";
						return super
								.createElementsQuery(query, filterPattern,
										limit).setParameter("agent", userId)
								.setParameter("mode", ACL.TYPE_READ);
					}
					return super.createElementsQuery(query, filterPattern,
							limit);
				}

				@Override
				protected void setCache(List<E> cache) {
					// do not cache the values
				}
			};
			injector.injectMembers(propertySet);
			return propertySet;
		}
		return super.createPropertySet(bean, uri, elementType, annotations);
	}
}
