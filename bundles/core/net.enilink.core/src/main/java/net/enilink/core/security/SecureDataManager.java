package net.enilink.core.security;

import java.util.ArrayList;
import java.util.List;

import com.google.inject.Inject;

import net.enilink.commons.iterator.IExtendedIterator;
import net.enilink.komma.dm.IDataManager;
import net.enilink.komma.dm.IDataManagerQuery;
import net.enilink.komma.em.ThreadLocalDataManager;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.core.IStatementPattern;
import net.enilink.komma.core.IValue;
import net.enilink.komma.core.KommaException;
import net.enilink.komma.core.URI;

/**
 * Data manager for an {@link ISecureModelSet}.
 */
public class SecureDataManager extends ThreadLocalDataManager {
	@Inject
	protected ISecureModelSet modelSet;

	protected IReference[] assertWritable(IReference... contexts) {
		URI userId = SecurityUtil.getUser();
		if (userId != null) {
			List<IReference> accessibleCtxs = new ArrayList<IReference>(
					contexts.length);
			for (IReference ctx : contexts) {
				if (modelSet.isWritableBy(ctx, userId)) {
					accessibleCtxs.add(ctx);
				}
			}
			if (accessibleCtxs.isEmpty() && contexts.length > 0) {
				throw new KommaException(
						"Writing to the default context has been denied.");
			}
			contexts = accessibleCtxs.toArray(new IReference[accessibleCtxs
					.size()]);
		}
		return contexts;
	}

	protected IReference[] assertReadable(IReference... contexts) {
		URI userId = SecurityUtil.getUser();
		if (userId != null) {
			List<IReference> accessibleCtxs = new ArrayList<IReference>(
					contexts.length);
			for (IReference ctx : contexts) {
				if (modelSet.isReadableBy(ctx, userId)) {
					accessibleCtxs.add(ctx);
				}
			}
			if (accessibleCtxs.isEmpty() && contexts.length > 0) {
				throw new KommaException(
						"Reading without a dataset has been denied.");
			}
			contexts = accessibleCtxs.toArray(new IReference[accessibleCtxs
					.size()]);
		}
		return contexts;
	}

	@Override
	public IDataManager add(Iterable<? extends IStatement> statements,
			IReference... contexts) {
		return super.add(statements, assertWritable(contexts));
	}

	@Override
	public IDataManager add(Iterable<? extends IStatement> statements,
			IReference[] readContexts, IReference... addContexts) {
		return super.add(statements, readContexts, assertWritable(addContexts));
	}

	@Override
	public <R> IDataManagerQuery<R> createQuery(String query, String baseURI,
			boolean includeInferred, IReference... contexts) {
		return super.createQuery(query, baseURI, includeInferred,
				assertReadable(contexts));
	}

	@Override
	public boolean hasMatch(IReference subject, IReference predicate,
			IValue object, boolean includeInferred, IReference... contexts) {
		return super.hasMatch(subject, predicate, object, includeInferred,
				assertReadable(contexts));
	}

	@Override
	public IExtendedIterator<IStatement> match(IReference subject,
			IReference predicate, IValue object, boolean includeInferred,
			IReference... contexts) {
		return super.match(subject, predicate, object, includeInferred,
				assertReadable(contexts));
	}

	@Override
	public IDataManager remove(
			Iterable<? extends IStatementPattern> statements,
			IReference... contexts) {
		return super.remove(statements, assertWritable(contexts));
	}
}
