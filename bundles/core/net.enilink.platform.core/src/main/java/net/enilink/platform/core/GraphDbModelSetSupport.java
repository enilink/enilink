package net.enilink.platform.core;

import java.util.Collection;

import net.enilink.composition.annotations.Iri;
import net.enilink.composition.traits.Behaviour;

import com.google.inject.AbstractModule;
import com.google.inject.Module;

import net.enilink.komma.core.*;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.model.MODELS;

/**
 * Implements GraphDB specific SPARQL extensions.
 */
@Iri(MODELS.NAMESPACE + "GraphDBModelSet")
public abstract class GraphDbModelSetSupport implements IModelSet,
		IModelSet.Internal, Behaviour<IModelSet> {
	static class OwlimDialect extends SparqlStandardDialect {
		@Override
		public QueryFragment fullTextSearch(
				Collection<? extends String> bindingNames, int flags,
				String... patterns) {
			StringBuilder combinedPatterns = new StringBuilder();
			for (String pattern : patterns) {
				if (pattern.isEmpty()) {
					continue;
				}
				if (! combinedPatterns.isEmpty()) {
					combinedPatterns.append(" OR ");
				}
				combinedPatterns.append(URIs.encodeSegment(pattern, true))
						// use wildcard for prefix matching with GraphDB full-text search
						.append("*");
			}
			StringBuilder sb = new StringBuilder();
			if (!combinedPatterns.isEmpty()) {
				for (String bindingName : bindingNames) {
					if (!sb.isEmpty()) {
						sb.append(" union ");
					}
					sb.append("{ ?").append(bindingName).append(" <http://www.ontotext.com/fts>")
							.append(" \"").append(combinedPatterns).append("\" }");
				}
			}
			return new QueryFragment(sb.toString());
		}
	}

	@Override
	public void collectInjectionModules(Collection<Module> modules, IGraph graph) {
		modules.add(new AbstractModule() {
			@Override
			protected void configure() {
				bind(OwlimDialect.class);
				bind(IDialect.class).to(OwlimDialect.class);
			}
		});
	}
}
