package net.enilink.platform.core;

import java.util.Collection;

import net.enilink.composition.annotations.Iri;
import net.enilink.composition.traits.Behaviour;

import com.google.inject.AbstractModule;
import com.google.inject.Module;

import net.enilink.komma.model.IModelSet;
import net.enilink.komma.model.MODELS;
import net.enilink.komma.core.IDialect;
import net.enilink.komma.core.QueryFragment;
import net.enilink.komma.core.SparqlStandardDialect;
import net.enilink.komma.core.URIs;

/**
 * Implements OWLIM specific SPARQL extensions.
 */
@Iri(MODELS.NAMESPACE + "OwlimSEModelSet")
public abstract class OwlimSeModelSetSupport implements IModelSet,
		IModelSet.Internal, Behaviour<IModelSet> {
	static class OwlimDialect extends SparqlStandardDialect {
		@Override
		public QueryFragment fullTextSearch(
				Collection<? extends String> bindingNames, int flags,
				String... patterns) {
			String matchFunc = (flags & CASE_SENSITIVE) != 0 ? "prefixMatch"
					: "prefixMatchIgnoreCase";
			StringBuilder patternsAsURI = new StringBuilder();
			for (String pattern : patterns) {
				if (pattern.isEmpty()) {
					continue;
				}
				patternsAsURI.append(
						URIs.encodeOpaquePart(pattern.replaceAll("[*?<>]", "")
								.trim().replaceAll("\\s+", ":"), true)).append(
						":");
			}
			StringBuilder sb = new StringBuilder();
			if (patternsAsURI.length() > 0) {
				for (String bindingName : bindingNames) {
					if (sb.length() > 0) {
						sb.append(" union ");
					}
					sb.append("{ <").append(patternsAsURI).append(">")
							.append(" <http://www.ontotext.com/owlim/fts#")
							.append(matchFunc).append("> ?")
							.append(bindingName).append(" }");
				}
			}
			return new QueryFragment(sb.toString());
		}
	}

	@Override
	public void collectInjectionModules(Collection<Module> modules) {
		modules.add(new AbstractModule() {
			@Override
			protected void configure() {
				bind(OwlimDialect.class);
				bind(IDialect.class).to(OwlimDialect.class);
			}
		});
	}
}
