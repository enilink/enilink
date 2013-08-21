package net.enilink.core;

import java.util.Collection;

import net.enilink.composition.traits.Behaviour;

import com.google.inject.AbstractModule;
import com.google.inject.Module;

import net.enilink.komma.model.IModelSet;
import net.enilink.komma.core.IDialect;
import net.enilink.komma.core.QueryFragment;
import net.enilink.komma.core.SparqlStandardDialect;

/**
 * Implements OWLIM specific SPARQL extensions.
 */
public abstract class OwlimDialectSupport implements IModelSet,
		IModelSet.Internal, Behaviour<IModelSet> {
	static class OwlimDialect extends SparqlStandardDialect {
		@Override
		public QueryFragment fullTextSearch(
				Collection<? extends String> bindingNames, int flags,
				String... patterns) {
			String matchFunc = (flags & CASE_INSENSITIVE) != 0 ? "prefixMatchIgnoreCase"
					: "prefixMatch";
			StringBuilder patternsAsURI = new StringBuilder();
			for (String pattern : patterns) {
				if (pattern.isEmpty()) {
					continue;
				}
				patternsAsURI.append(
						pattern.replaceAll("[*?<>]", "").trim()
								.replaceAll("\\s+", ":")).append(":");
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
