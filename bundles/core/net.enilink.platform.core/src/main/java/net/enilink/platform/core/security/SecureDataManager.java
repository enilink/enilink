package net.enilink.platform.core.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import net.enilink.commons.iterator.IExtendedIterator;
import net.enilink.commons.iterator.IMap;
import net.enilink.commons.iterator.NiceIterator;
import net.enilink.commons.iterator.WrappedIterator;
import net.enilink.komma.core.IBindings;
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
	/**
	 * Adds a statement.
	 */
	static class AddOp extends Op {
		AddOp(IStatement stmt) {
			super(stmt);
		}

		@Override
		boolean isAdd() {
			return true;
		}
	}

	/**
	 * An operation that adds or removes a statement or statement pattern.
	 */
	static abstract class Op {
		WriteMode mode = WriteMode.NONE;
		final IStatementPattern stmt;

		Op(IStatementPattern stmt) {
			this.stmt = stmt;
		}

		abstract boolean isAdd();
	}

	/**
	 * Removes a statement pattern.
	 */
	static class RemoveOp extends Op {
		RemoveOp(IStatementPattern stmt) {
			super(stmt);
		}

		@Override
		boolean isAdd() {
			return false;
		}
	}

	/**
	 * Encapsulates a set of add and remove operations for a certain user and
	 * given contexts.
	 */
	class SecureOps {
		/**
		 * Feeds a set of statements or statement patterns into an
		 * {@link IDataManager}'s add and remove methods.
		 */
		class StmtIterator<S extends IStatementPattern> extends NiceIterator<S> {
			final boolean isAdd;
			S stmt;

			StmtIterator(boolean isAdd) {
				this.isAdd = isAdd;
			}

			@SuppressWarnings("unchecked")
			@Override
			public boolean hasNext() {
				if (stmt == null && loadNextOp() && nextOp.isAdd() == isAdd) {
					stmt = (S) nextOp.stmt;
					nextOp = null;
				}
				return stmt != null;
			}

			@Override
			public S next() {
				if (stmt == null) {
					noElements("No more statements available.");
				}
				S next = stmt;
				stmt = null;
				return next;
			}
		}

		/**
		 * Operations that are blocked until the access mode of their
		 * subject-resource is known.
		 */
		final Map<IReference, Collection<Op>> blockedOps = new HashMap<>();

		/**
		 * Queue of operations that are valid for execution.
		 */
		final Queue<Op> checkedOps = new LinkedList<>();

		final IReference[] contexts;

		Op nextOp;

		IExtendedIterator<Op> ops = WrappedIterator.emptyIterator();

		final Map<IReference, WriteMode> resourceModes = new HashMap<>();

		// immediately capture the current user
		final URI userId = SecurityUtil.getUser();

		// TODO also respect readContexts for add operations
		SecureOps(IReference[] contexts) {
			this.contexts = contexts;
		}

		/**
		 * Executes a SPARQL query to retrieve the current ACL modes for the
		 * given <code>resource</code>.
		 * 
		 * @param resource
		 *            The resource for which the ACL modes should be retrieved
		 * @return A set of ACL modes
		 */
		Set<IReference> aclModes(IReference resource) {
			IDataManagerQuery<?> aclQuery = createQuery(
					SecurityUtil.QUERY_ACLMODE, "base:", false, contexts);
			aclQuery.setParameter("target", resource).setParameter("user",
					userId);
			return aclQuery.evaluate().mapWith(new IMap<Object, IReference>() {
				@Override
				public IReference map(Object value) {
					return (IReference) ((IBindings<?>) value).get("mode");
				}
			}).toSet();
		}

		/**
		 * Add statements which should be inserted.
		 * 
		 * @param stmts
		 *            The statements to be inserted
		 */
		void addStatements(Iterator<? extends IStatement> stmts) {
			this.ops = this.ops.andThen(WrappedIterator.create(stmts).mapWith(
					new IMap<IStatement, Op>() {
						@Override
						public Op map(IStatement stmt) {
							return new AddOp(stmt);
						}
					}));
		}

		/**
		 * Enqueues a statement that is blocked on its subject.
		 * 
		 * @param stmt
		 *            The statement
		 */
		void block(Op op) {
			IReference s = op.stmt.getSubject();
			Collection<Op> blocked = blockedOps.get(s);
			if (blocked == null) {
				blocked = new ArrayList<>(2);
				blockedOps.put(s, blocked);
			}
			blocked.add(op);
		}

		/**
		 * Writes immediately writable statements to the underlying data
		 * manager. Then the function computes root nodes for which the write
		 * modes are computed. These write modes are transitively propagated to
		 * dependent nodes (spreading activation).
		 */
		void execute() {
			// add potentially blocked statements
			flushOperations();
			while (!blockedOps.isEmpty()) {
				// find root nodes
				Set<IReference> roots = new HashSet<>(blockedOps.keySet());
				for (Iterable<Op> opList : blockedOps.values()) {
					for (Op op : opList) {
						IStatementPattern stmt = op.stmt;
						Object o = stmt.getObject();
						if (o instanceof IReference
								&& (!stmt.getSubject().equals(o))) {
							roots.remove(o);
						}
					}
				}
				for (IReference resource : roots) {
					WriteMode writeMode = writeMode(resource);
					if (writeMode == WriteMode.NONE) {
						writeMode = writeModeFromChain(resource);
					}
					if (writeMode != WriteMode.NONE) {
						unlock(resource, writeMode);
						continue;
					}
					// test if all blocked operations where remove
					// operations
					boolean onlyRemove = true;
					for (Op op : blockedOps.remove(resource)) {
						if (op.isAdd()) {
							onlyRemove = false;
							break;
						}
					}
					if (onlyRemove
							&& !hasMatch(resource, null, null, false, contexts)) {
						// no statements exists about the resource, so don't
						// care
					} else {
						throw new KommaException("Changing the resource "
								+ resource + " is not allowed.");
					}
				}
				flushOperations();
			}
			if (!blockedOps.isEmpty()) {
				throw new KommaException(
						"Some statements violate the security constraints.");
			}
		}

		/**
		 * Writes some statements to the underlying data manager.
		 */
		void flushOperations() {
			while (loadNextOp()) {
				if (nextOp.isAdd()) {
					SecureDataManager.super.add(new StmtIterator<IStatement>(
							true), contexts, contexts);
				} else {
					SecureDataManager.super.remove(new StmtIterator<>(false),
							contexts);
				}
			}
		}

		/**
		 * Computes the next immediately executable operation.
		 * 
		 * @return <code>true</code> if some operation can be executed, else
		 *         <code>false</code>.
		 */
		boolean loadNextOp() {
			if (nextOp == null) {
				WriteMode writeMode = WriteMode.NONE;
				if (!checkedOps.isEmpty()) {
					nextOp = checkedOps.remove();
					writeMode = nextOp.mode;
				}
				while (nextOp == null && ops.hasNext()) {
					Op op = ops.next();
					boolean isAdd = op.isAdd();
					IStatementPattern stmt = op.stmt;
					IReference s = stmt.getSubject();
					if (!isAdd && (s == null ||
					// required for special check in case of ACLs
							stmt.getObject() == null
									&& WEBACL.PROPERTY_ACCESSTO.equals(stmt
											.getPredicate()))) {
						// retrieve concrete statements for remove
						// operations
						ops = WrappedIterator
								.create(match(s, stmt.getPredicate(),
										(IValue) stmt.getObject(), false,
										contexts).toList().iterator())
								.mapWith(new IMap<IStatement, Op>() {
									@Override
									public Op map(IStatement stmt) {
										return new RemoveOp(stmt);
									}
								}).andThen(ops);
						continue;
					}
					writeMode = writeMode(op);
					if (isAdd && writeMode != WriteMode.NONE
							|| writeMode == WriteMode.MODIFY) {
						nextOp = op;
					} else if (s.getURI() == null) {
						block(op);
					} else {
						throw new KommaException(
								"Modification is denied for resource: " + s);
					}
				}
				if (nextOp != null) {
					Object o = nextOp.stmt.getObject();
					if (o == null) {
						// retrieve concrete statements for remove
						// operations
						for (IStatement stmt : match(nextOp.stmt.getSubject(),
								nextOp.stmt.getPredicate(), null, false,
								contexts)) {
							o = stmt.getObject();
							if (o instanceof IReference) {
								unlock((IReference) o, writeMode);
							}
						}
					} else if (o instanceof IReference) {
						unlock((IReference) o, writeMode);
					}
				}
				return nextOp != null;
			} else {
				return true;
			}

		}

		/**
		 * Add statements which should be removed.
		 * 
		 * @param stmts
		 *            The statement patterns to be removed
		 */
		void removeStatements(Iterator<? extends IStatementPattern> stmts) {
			this.ops = this.ops.andThen(WrappedIterator.create(stmts).mapWith(
					new IMap<IStatementPattern, Op>() {
						@Override
						public Op map(IStatementPattern stmt) {
							return new RemoveOp(stmt);
						}
					}));
		}

		void setModes(Collection<IReference> elements, WriteMode mode) {
			for (IReference elem : elements) {
				resourceModes.put(elem, mode);
			}
		}

		/**
		 * Unlocks a resource with the given write mode.
		 * 
		 * This enqueues the corresponding statements for addition or removal.
		 * 
		 */
		void unlock(IReference resource, WriteMode writeMode) {
			if (resource.getURI() == null) {
				resourceModes.put(resource, writeMode);
				Iterable<Op> blocked = blockedOps.remove(resource);
				if (blocked != null) {
					for (Op op : blocked) {
						// test if write mode is sufficient for remove
						// operations
						if (!op.isAdd() && writeMode != WriteMode.MODIFY) {
							throw new KommaException(
									"Insufficient access rights for execution a remove operation with the pattern: "
											+ op.stmt);
						}
						op.mode = writeMode;
						checkedOps.add(op);
						Object o = op.stmt.getObject();
						if (o instanceof IReference) {
							resourceModes.put((IReference) o, writeMode);
						}
					}
				}
			}
		}

		/**
		 * Determine the write mode for the given resource.
		 * 
		 * If the given resource is a blank node then only the cached values are
		 * used. Otherwise a query is executed against the underlying data
		 * manager.
		 * 
		 * @param resource
		 *            The resource for which the write mode should be
		 *            determined.
		 * @return The write mode for the given resource
		 */
		WriteMode writeMode(IReference resource) {
			WriteMode writeMode = resourceModes.get(resource);
			if (writeMode != null) {
				return writeMode;
			}
			if (resource.getURI() == null) {
				// ACLs for blank nodes are computed on commit
				return WriteMode.NONE;
			}
			if (SecurityUtil.SYSTEM_USER.equals(userId)) {
				// the system has always sufficient access rights
				return WriteMode.MODIFY;
			}
			if (resource.equals(userId)) {
				// users may always change their own data
				// Is this correct?
				return WriteMode.MODIFY;
			}
			IDataManagerQuery<?> aclQuery = createQuery(
					SecurityUtil.QUERY_ACLMODE, "base:", false, contexts);
			aclQuery.setParameter("target", resource).setParameter("user",
					userId);
			Set<?> modes = aclModes(resource);
			if (modes.contains(WEBACL.MODE_CONTROL)
					|| modes.contains(WEBACL.MODE_WRITE)) {
				writeMode = WriteMode.MODIFY;
			} else if (modes.contains(WEBACL.MODE_APPEND)) {
				writeMode = WriteMode.MODIFY;
			} else {
				writeMode = WriteMode.NONE;
			}
			resourceModes.put(resource, writeMode);
			return writeMode;
		}

		/**
		 * Determines the write mode for a given operation.
		 * 
		 * This method inspects the statement to prevent illegal references to
		 * foreign blank nodes and to control the modification of ACLs.
		 * 
		 * @param op
		 *            The operation for which the write mode should be
		 *            determined
		 * @return The write mode for the given operation
		 */
		WriteMode writeMode(Op op) {
			IStatementPattern stmt = op.stmt;
			boolean isAdd = op.isAdd();
			IValue o = (IValue) stmt.getObject();
			if (isAdd) {
				if (o instanceof IReference
						&& ((IReference) o).getURI() == null
						&& !resourceModes.containsKey(o)) {
					// check if the blank node is referenced somewhere in the
					// store
					if (hasMatch(null, null, o, false, contexts)) {
						// test if a persistent chain exists from a writable
						// resource
						if (writeModeFromChain((IReference) o) == WriteMode.NONE) {
							throw new KommaException(
									"Write access to blank node has been denied: "
											+ o);
						}
					}
				}
			}
			// restrict the modification of ACLs
			IReference p = stmt.getPredicate();
			if (isAdd && WEBACL.PROPERTY_ACCESSTOCLASS.equals(p)) {
				throw new KommaException(
						"Adding access contraints for classes of resources is not allowed.");
			} else if (WEBACL.PROPERTY_ACCESSTO.equals(p)) {
				if (o instanceof IReference
						&& (userId.equals(o) || aclModes((IReference) o)
								.contains(WEBACL.MODE_CONTROL))) {
					if (stmt.getSubject().getURI() == null
							&& !hasMatch(null, null, stmt.getSubject(), false,
									contexts)) {
						// if ACL is a blank node and not referenced by any
						// other resource then it is directly writable,
						// else the normal access rules are also used for the
						// ACL resource
						resourceModes.put(stmt.getSubject(), WriteMode.MODIFY);
						return WriteMode.MODIFY;
					}
				} else {
					throw new KommaException(
							"Modifying access constraint of the resource " + o
									+ " has been denied.");
				}
			}
			return writeMode(stmt.getSubject());
		}

		/**
		 * Computes the write mode for a given resource by using a chain of
		 * blank nodes to some writable named resource.
		 * 
		 * writable(r1) -> b1 -> b2 -> resource
		 */
		WriteMode writeModeFromChain(IReference resource) {
			Set<IReference> seen = new HashSet<>();
			Queue<IReference> chain = new LinkedList<>();
			chain.add(resource);
			seen.add(resource);
			while (!chain.isEmpty()) {
				IReference r = chain.remove();
				try (IExtendedIterator<IStatement> stmts = match(null, null, r,
						false, contexts)) {
					for (IStatement stmt : stmts) {
						IReference s = stmt.getSubject();
						boolean isBlank = s.getURI() == null;
						WriteMode mode;
						if (isBlank) {
							mode = resourceModes.get(s);
						} else {
							mode = writeMode(s);
						}
						if (mode == null && isBlank) {
							// mode is unknown for the given blank node
							// keep on searching for a known mode
							if (seen.add(s)) {
								chain.add(s);
							}
						} else {
							if (mode == null) {
								mode = WriteMode.NONE;
							}
							setModes(seen, mode);
							return mode;
						}
					}
				}
			}
			setModes(seen, WriteMode.NONE);
			return WriteMode.NONE;
		}
	};

	static class SecureOpsInfo {
		Map<Set<IReference>, SecureOps> contextsToOps = new LinkedHashMap<>();
	}

	static enum WriteMode {
		ADD, MODIFY, NONE
	}

	final static Set<IReference> removeModes = new HashSet<IReference>(
			Arrays.asList(WEBACL.MODE_WRITE, WEBACL.MODE_CONTROL,
					ENILINKACL.MODE_WRITERESTRICTED));

	final static Set<IReference> writeModes = new HashSet<IReference>(
			Arrays.asList(WEBACL.MODE_WRITE, WEBACL.MODE_CONTROL,
					WEBACL.MODE_APPEND, ENILINKACL.MODE_WRITERESTRICTED));

	final IDataManager delegate;

	protected ISecureModelSet modelSet;

	protected SecureTransaction secureTransaction;

	final Map<IReference, SecureOpsInfo> userToOperations = new HashMap<>();

	public SecureDataManager(ISecureModelSet modelSet, IDataManager delegate) {
		this.modelSet = modelSet;
		this.delegate = delegate;
	}

	@Override
	public IDataManager add(Iterable<? extends IStatement> statements,
			IReference... contexts) {
		SecureOps secureOps = assertModes(contexts, contexts, writeModes);
		if (secureOps != null) {
			secureOps.addStatements(statements.iterator());
		} else {
			super.add(statements, contexts);
		}
		if (secureOps != null && !secureTransaction.isActive()) {
			secureOps.execute();
		}
		return this;
	}

	@Override
	public IDataManager add(Iterable<? extends IStatement> statements,
			IReference[] readContexts, IReference... addContexts) {
		SecureOps secureOps = assertModes(readContexts, addContexts, writeModes);
		if (secureOps != null) {
			secureOps.addStatements(statements.iterator());
		} else {
			super.add(statements, assertReadable(readContexts), addContexts);
		}
		if (secureOps != null && !secureTransaction.isActive()) {
			secureOps.execute();
		}
		return this;
	}

	protected SecureOps assertModes(IReference[] readContexts,
			IReference[] contexts, Set<IReference> modes) {
		URI userId = SecurityUtil.getUser();
		boolean restrictedMode = false;
		if (contexts.length == 0) {
			throw new KommaException(
					"Writing to the default context has been denied.");
		}
		for (IReference ctx : contexts) {
			IReference actualMode = modelSet.writeModeFor(ctx, userId);
			if (actualMode != null && modes.contains(actualMode)) {
				restrictedMode |= ENILINKACL.MODE_WRITERESTRICTED
						.equals(actualMode);
			} else {
				throw new KommaException("Accessing the context " + ctx
						+ " has been denied.");
			}
		}
		if (restrictedMode) {
			SecureOps secureOps;
			if (super.getTransaction().isActive()) {
				secureOps = secureOps(SecurityUtil.getUser(), contexts);
			} else {
				secureOps = new SecureOps(contexts);
			}
			return secureOps;
		}
		return null;
	}

	protected IReference[] assertReadable(IReference... contexts) {
		URI userId = SecurityUtil.getUser();
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
		return accessibleCtxs.toArray(new IReference[accessibleCtxs.size()]);
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
					try {
						for (SecureOpsInfo opInfo : userToOperations.values()) {
							if (!opInfo.contextsToOps.isEmpty()) {
								for (Map.Entry<?, SecureOps> entry : opInfo.contextsToOps
										.entrySet()) {
									entry.getValue().execute();
								}
							}
						}
					} finally {
						userToOperations.clear();
					}
					super.commit();
				}

				@Override
				public void rollback() {
					userToOperations.clear();
					super.rollback();
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
		SecureOps secureOps = assertModes(contexts, contexts, removeModes);
		if (secureOps != null) {
			secureOps.removeStatements(statements.iterator());
		} else {
			super.remove(statements, contexts);
		}
		if (secureOps != null && !secureTransaction.isActive()) {
			secureOps.execute();
		}
		return this;
	}

	SecureOps secureOps(IReference user, IReference[] contexts) {
		SecureOpsInfo opsInfo = userToOperations.get(user);
		if (opsInfo == null) {
			opsInfo = new SecureOpsInfo();
			userToOperations.put(user, opsInfo);
		}
		Set<IReference> key = new HashSet<>(Arrays.asList(contexts));
		SecureOps ops = opsInfo.contextsToOps.get(key);
		if (ops == null) {
			ops = new SecureOps(contexts);
			opsInfo.contextsToOps.put(key, ops);
		}
		return ops;
	}
}
