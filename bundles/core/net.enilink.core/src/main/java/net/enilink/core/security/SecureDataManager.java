package net.enilink.core.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import net.enilink.commons.iterator.IExtendedIterator;
import net.enilink.commons.iterator.NiceIterator;
import net.enilink.commons.iterator.WrappedIterator;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.core.IStatementPattern;
import net.enilink.komma.core.ITransaction;
import net.enilink.komma.core.IValue;
import net.enilink.komma.core.KommaException;
import net.enilink.komma.core.URI;
import net.enilink.komma.dm.IDataManager;
import net.enilink.komma.dm.IDataManagerQuery;
import net.enilink.komma.em.DelegatingDataManager;
import net.enilink.vocab.acl.ENILINKACL;
import net.enilink.vocab.acl.WEBACL;

/**
 * Data manager for an {@link ISecureModelSet}.
 */
public class SecureDataManager extends DelegatingDataManager {
	class SecureAdd extends SecureStmts<IStatement> {
		final IReference[] readContexts;

		SecureAdd(IReference[] readContexts, IReference[] addContexts) {
			super(addContexts);
			this.readContexts = readContexts;
		}

		@Override
		void handleStatements() {
			SecureDataManager.super.add(this, readContexts, contexts);
		}
	}

	class SecureRemove extends SecureStmts<IStatementPattern> {
		SecureRemove(IReference[] contexts) {
			super(contexts);
		}

		@Override
		void handleStatements() {
			SecureDataManager.super.remove(this, contexts);
		}
	}

	abstract class SecureStmts<S extends IStatementPattern> extends
			NiceIterator<S> {
		final Map<IReference, Iterable<S>> blockedStmts = new HashMap<>();

		final IReference[] contexts;

		S next;

		IExtendedIterator<S> stmts = WrappedIterator.emptyIterator();

		final Set<IReference> writableResources = new HashSet<>();

		final Queue<S> writableStmts = new LinkedList<>();

		SecureStmts(IReference[] addContexts) {
			this.contexts = addContexts;
		}

		void addStatements(Iterator<? extends S> stmts) {
			this.stmts = this.stmts.andThen(stmts);
		}

		/**
		 * Enqueues a statement that is blocked on its subject.
		 * 
		 * @param stmt
		 *            The statement
		 */
		@SuppressWarnings("unchecked")
		void block(S stmt) {
			IReference s = stmt.getSubject();
			Iterable<S> blocked = blockedStmts.get(s);
			if (blocked != null) {
				if (blocked instanceof List<?>) {
					((List<S>) blocked).add(stmt);
				} else {
					List<S> list = new ArrayList<>(2);
					list.add((S) blocked);
					list.add(stmt);
					blockedStmts.put(s, list);
				}
			} else if (s instanceof Iterable<?>) {
				// optimize storing of single statements
				blockedStmts.put(s, (Iterable<S>) stmt);
			} else {
				List<S> list = new ArrayList<>(1);
				list.add(stmt);
				blockedStmts.put(s, list);
			}
		}

		void commit() {
			// add potentially blocked statements
			handleStatements();
			while (!blockedStmts.isEmpty()) {
				// find root nodes
				Set<IReference> roots = new HashSet<>(blockedStmts.keySet());
				for (Iterable<S> stmtList : blockedStmts.values()) {
					for (S stmt : stmtList) {
						Object o = stmt.getObject();
						if (o instanceof IReference
								&& (!stmt.getSubject().equals(o))) {
							roots.remove(o);
						}
					}
				}
				for (IReference resource : roots) {
					boolean writable = isWritable(resource);
					if (!writable) {
						Set<IReference> seen = new HashSet<>();
						Queue<IReference> chain = new LinkedList<>();
						chain.add(resource);
						seen.add(resource);
						while (!chain.isEmpty()) {
							IReference r = chain.remove();
							for (IStatement stmt : match(null, null, r, false,
									contexts)) {
								IReference s = stmt.getSubject();
								if (isWritable(s)) {
									// also mark resource as writable
									writableResources.add(resource);
									writable = true;
									break;
								} else if (s.getURI() == null) {
									if (seen.add(s)) {
										chain.add(s);
									}
								} else {
									break;
								}
							}
						}
					}

					if (writable) {
						unlock(resource);
					} else {
						throw new KommaException("Changing the resource "
								+ resource + " is not allowed.");
					}
				}
				handleStatements();
			}
			if (!blockedStmts.isEmpty()) {
				throw new KommaException(
						"Some statements violate the security constraints.");
			}
		}

		abstract void handleStatements();

		@SuppressWarnings("unchecked")
		@Override
		public boolean hasNext() {
			if (next == null) {
				if (!writableStmts.isEmpty()) {
					next = writableStmts.remove();
				}
				while (next == null && stmts.hasNext()) {
					S stmt = stmts.next();
					IReference s = stmt.getSubject();
					if (s == null) {
						// retrieve concrete statements for remove operations
						stmts = WrappedIterator.create(
								(Iterator<S>) match(null, stmt.getPredicate(),
										(IValue) stmt.getObject(), false,
										contexts).toList().iterator()).andThen(
								stmts);
						continue;
					}
					if (isWritable(s)) {
						next = stmt;
					} else if (s.getURI() == null) {
						block(stmt);
					} else {
						throw new KommaException(
								"Modification is denied for resource: " + s);
					}
				}
				if (next != null) {
					Object o = next.getObject();
					if (o == null) {
						// retrieve concrete statements for remove operations
						for (IStatement stmt : match(next.getSubject(),
								next.getPredicate(), null, false, contexts)) {
							o = stmt.getObject();
							if (o instanceof IReference) {
								unlock((IReference) o);
							}
						}
					} else if (o instanceof IReference) {
						unlock((IReference) o);
					}
				}
				return next != null;
			} else {
				return true;
			}

		}

		protected boolean isWritable(IReference resource) {
			if (writableResources.contains(resource)) {
				return true;
			}
			if (resource.getURI() == null) {
				return false;
			}
			URI userId = SecurityUtil.getUser();
			if (SecurityUtil.SYSTEM_USER.equals(userId)) {
				// the system has always sufficient access rights
				return true;
			}
			if (resource.equals(userId)) {
				// users may always change their own data
				// Is this correct?
				return true;
			}
			if (userId != null) {
				IDataManagerQuery<?> aclQuery = createQuery(
						SecurityUtil.QUERY_ACLMODE, "base:", false, contexts);
				aclQuery.setParameter("target", resource).setParameter("user",
						userId);
				Set<?> modes = aclQuery.evaluate().toSet();
				for (IReference mode : writeModes) {
					if (modes.contains(mode)) {
						writableResources.add(resource);
						return true;
					}
				}
				return false;
			}
			return true;
		}

		@Override
		public S next() {
			S result = next;
			if (result == null) {
				noElements("No statements are available.");
			}
			next = null;
			return result;
		}

		void unlock(IReference resource) {
			if (resource.getURI() == null) {
				writableResources.add(resource);
				Iterable<S> blocked = blockedStmts.remove(resource);
				if (blocked != null) {
					for (S stmt : blocked) {
						writableStmts.add(stmt);
						Object o = stmt.getObject();
						if (o instanceof IReference) {
							writableResources.add((IReference) o);
						}
					}
				}
			}
		}
	}

	final static Set<IReference> removeModes = new HashSet<IReference>(
			Arrays.asList(WEBACL.MODE_WRITE, WEBACL.MODE_CONTROL,
					ENILINKACL.MODE_RESTRICTED));

	final static Set<IReference> writeModes = new HashSet<IReference>(
			Arrays.asList(WEBACL.MODE_WRITE, WEBACL.MODE_CONTROL,
					WEBACL.MODE_APPEND, ENILINKACL.MODE_RESTRICTED));

	final IDataManager delegate;

	protected ISecureModelSet modelSet;

	final Map<Set<IReference>, SecureAdd> secureAddMap = new HashMap<>();

	final Map<Set<IReference>, SecureRemove> secureRemoveMap = new HashMap<>();

	protected SecureTransaction secureTransaction;

	public SecureDataManager(ISecureModelSet modelSet, IDataManager delegate) {
		this.modelSet = modelSet;
		this.delegate = delegate;
	}

	@Override
	public IDataManager add(Iterable<? extends IStatement> statements,
			IReference... contexts) {
		SecureStmts<IStatement> secureAdd = assertAddable(contexts, contexts,
				writeModes);
		if (secureAdd != null) {
			secureAdd.addStatements(statements.iterator());
			statements = secureAdd;
		}
		super.add(statements, contexts);
		if (secureAdd != null && !secureTransaction.isActive()) {
			secureAdd.commit();
		}
		return this;
	}

	@Override
	public IDataManager add(Iterable<? extends IStatement> statements,
			IReference[] readContexts, IReference... addContexts) {
		SecureStmts<IStatement> secureAdd = assertAddable(readContexts,
				addContexts, writeModes);
		if (secureAdd != null) {
			secureAdd.addStatements(statements.iterator());
			statements = secureAdd;
		}
		super.add(statements, assertReadable(readContexts), addContexts);
		if (secureAdd != null && !secureTransaction.isActive()) {
			secureAdd.commit();
		}
		return this;
	}

	protected SecureAdd assertAddable(IReference[] readContexts,
			IReference[] contexts, Set<IReference> modes) {
		boolean restrictedMode = assertModes(contexts, modes);
		if (restrictedMode) {
			Set<IReference> key = new HashSet<>(Arrays.asList(contexts));
			SecureAdd secureAdd = secureAddMap.get(key);
			if (secureAdd == null) {
				secureAdd = new SecureAdd(readContexts, contexts);
				if (super.getTransaction().isActive()) {
					secureAddMap.put(key, secureAdd);
				}
			}
			return secureAdd;
		}
		return null;
	}

	protected boolean assertModes(IReference[] contexts, Set<IReference> modes) {
		URI userId = SecurityUtil.getUser();
		boolean restrictedMode = false;
		if (userId != null) {
			if (contexts.length == 0) {
				throw new KommaException(
						"Writing to the default context has been denied.");
			}
			for (IReference ctx : contexts) {
				IReference actualMode = modelSet.writeModeFor(ctx, userId);
				if (actualMode != null && modes.contains(actualMode)) {
					restrictedMode |= ENILINKACL.MODE_RESTRICTED
							.equals(actualMode);
				} else {
					throw new KommaException("Accessing the context " + ctx
							+ " has been denied.");
				}
			}
		}
		return restrictedMode;
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

	protected SecureRemove assertRemovable(IReference[] contexts,
			Set<IReference> modes) {
		boolean restrictedMode = assertModes(contexts, modes);
		if (restrictedMode) {
			Set<IReference> key = new HashSet<>(Arrays.asList(contexts));
			SecureRemove secureRemove = secureRemoveMap.get(key);
			if (secureRemove == null) {
				secureRemove = new SecureRemove(contexts);
				if (super.getTransaction().isActive()) {
					secureRemoveMap.put(key, secureRemove);
				}
			}
			return secureRemove;
		}
		return null;
	}

	@Override
	public void close() {
		secureTransaction = null;
		super.close();
	}

	@Override
	public <R> IDataManagerQuery<R> createQuery(String query, String baseURI,
			boolean includeInferred, IReference... contexts) {
		return super.createQuery(query, baseURI, includeInferred,
				assertReadable(contexts));
	}

	@Override
	public IDataManager getDelegate() {
		return delegate;
	}

	@Override
	public ITransaction getTransaction() {
		if (secureTransaction == null) {
			secureTransaction = new SecureTransaction(super.getTransaction()) {
				@Override
				public void commit() {
					if (!secureAddMap.isEmpty()) {
						for (Map.Entry<?, SecureAdd> entry : secureAddMap
								.entrySet()) {
							entry.getValue().commit();
						}
						secureAddMap.clear();
					}
					if (!secureRemoveMap.isEmpty()) {
						for (Map.Entry<?, SecureRemove> entry : secureRemoveMap
								.entrySet()) {
							entry.getValue().commit();
						}
						secureRemoveMap.clear();
					}
					super.commit();
				}
			};
		}
		return secureTransaction;
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
		SecureRemove secureRemove = assertRemovable(contexts, removeModes);
		if (secureRemove != null) {
			secureRemove.addStatements(statements.iterator());
			statements = secureRemove;
		}
		super.remove(statements, contexts);
		if (secureRemove != null && !secureTransaction.isActive()) {
			secureRemove.commit();
		}
		return this;
	}
}
