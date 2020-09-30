package net.enilink.platform.core.edit;

import net.enilink.komma.common.command.ICommand;
import net.enilink.komma.common.command.IdentityCommand;
import net.enilink.komma.core.*;
import net.enilink.komma.edit.assist.IContentProposal;
import net.enilink.komma.edit.assist.IContentProposalProvider;
import net.enilink.komma.edit.properties.IEditingSupport;
import net.enilink.komma.edit.properties.IProposalSupport;
import net.enilink.komma.edit.properties.ResourceEditingSupport;
import net.enilink.komma.edit.provider.IItemLabelProvider;
import net.enilink.komma.edit.provider.ReflectiveItemProviderAdapterFactory;
import net.enilink.komma.em.concepts.IClass;
import net.enilink.komma.em.util.ISparqlConstants;
import net.enilink.platform.core.security.SecurityUtil;
import net.enilink.vocab.acl.WEBACL;
import net.enilink.vocab.foaf.Agent;
import net.enilink.vocab.foaf.FOAF;

import java.util.Arrays;
import java.util.Collection;

public class AuthAdapterFactory extends ReflectiveItemProviderAdapterFactory {
	public AuthAdapterFactory() {
		super(EnilinkEditPlugin.INSTANCE, FOAF.NAMESPACE_URI);
		this.supportedTypes.add(IEditingSupport.class);
	}

	public Object adapt(Object object, Object type) {
		if ((WEBACL.PROPERTY_AGENT.equals(object) || WEBACL.PROPERTY_OWNER.equals(object)) &&
				IEditingSupport.class.equals(type)) {
			return new ResourceEditingSupport(this) {
				@Override
				public ICommand convertEditorValue(Object editorValue, IEntityManager entityManager, Object element) {
					String name = editorValue.toString();
					if (name.contains(":")) {
						if (! name.startsWith("enilink:")) {
							name = "enilink:" + name;
						}
						return new IdentityCommand(URIs.createURI(name));
					}
					return super.convertEditorValue(editorValue, entityManager, element);
				}

				@Override
				public IProposalSupport getProposalSupport(Object element) {
					final IItemLabelProvider resourceLabelProvider = super.getProposalSupport(element).getLabelProvider();
					return new IProposalSupport() {
						@Override
						public IItemLabelProvider getLabelProvider() {
							return new IItemLabelProvider() {
								public String getText(Object object) {
									return object instanceof ResourceProposal ? resourceLabelProvider.getText(object) : ((IContentProposal) object).getLabel();
								}

								public Object getImage(Object object) {
									return object instanceof ResourceProposal ? resourceLabelProvider.getImage(object) : null;
								}
							};
						}

						@Override
						public IContentProposalProvider getProposalProvider() {
							IStatement stmt = getStatement(element);
							IEntity target = (IEntity) stmt.getSubject();
							return (contents, position) -> {
								IDialect dialect = target.getEntityManager().getFactory().getDialect();
								QueryFragment searchPatterns = dialect.fullTextSearch(Arrays.asList("agent"), IDialect.DEFAULT, contents.substring(0, position));
								IQuery<?> query = target.getEntityManager().createQuery(ISparqlConstants.PREFIX + //
										"prefix foaf: <" + FOAF.NAMESPACE + "> " + //
										"select ?agent { ?agent a ?type . " + //
										"{ ?type rdfs:subClassOf* foaf:Agent } union { ?type rdfs:subClassOf* foaf:Group } . " + //
										searchPatterns + //
										"filter isIri(?agent)" + //
										" }");
								searchPatterns.addParameters(query);
								return query.evaluate(IEntity.class).toList().stream()
										.map(agent -> {
											String label = getLabel(agent);
											return new ResourceProposal(label, label.length(), agent);
										}).toArray(IContentProposal[]::new);
							};
						}

						@Override
						public char[] getAutoActivationCharacters() {
							return new char[0];
						}
					};
				}
			};
		}
		return super.adapt(object, type);
	}

	@Override
	protected Object createItemProvider(Object object, Collection<IClass> types, Object providerType) {
		if (types.contains(FOAF.TYPE_AGENT) || types.contains(FOAF.TYPE_GROUP)) {
			return new AuthItemProvider(this, resourceLocator, types);
		}
		return null;
	}
}