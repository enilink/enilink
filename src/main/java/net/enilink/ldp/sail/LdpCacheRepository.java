package net.enilink.ldp.sail;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.openrdf.model.Namespace;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.query.BooleanQuery;
import org.openrdf.query.GraphQuery;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.query.Query;
import org.openrdf.query.QueryLanguage;
import org.openrdf.query.TupleQuery;
import org.openrdf.query.Update;
import org.openrdf.repository.Repository;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.openrdf.repository.UnknownTransactionStateException;
import org.openrdf.repository.base.RepositoryBase;
import org.openrdf.repository.base.RepositoryConnectionBase;
import org.openrdf.rio.RDFHandler;
import org.openrdf.rio.RDFHandlerException;

import info.aduna.iteration.CloseableIteratorIteration;

/**
 * Repository to be used as federation member for the LDP cache.
 * <p>
 * Intercepts queries for LDP resources, queries the source to update the
 * underlying cache repository (iff necessary), returns matching statements
 * using the underlying cache repository.
 */
public class LdpCacheRepository extends RepositoryBase {

	protected File dataDir;
	protected ValueFactory valueFactory;

	@Override
	public RepositoryConnection getConnection() throws RepositoryException {
		return new LdpCacheConnection(this);
	}

	@Override
	public File getDataDir() {
		return dataDir;
	}

	@Override
	public ValueFactory getValueFactory() {
		if (valueFactory == null) {
			valueFactory = new ValueFactoryImpl();
		}
		return valueFactory;
	}

	@Override
	public boolean isWritable() throws RepositoryException {
		return false;
	}

	@Override
	public void setDataDir(File dataDir) {
		this.dataDir = dataDir;
	}

	@Override
	protected void initializeInternal() throws RepositoryException {
		LdpCache.getInstance();
	}

	@Override
	protected void shutDownInternal() throws RepositoryException {
	}

	/**
	 * RepositoryConnection wrapping the LDP cache.
	 */
	public static class LdpCacheConnection extends RepositoryConnectionBase {

		protected boolean isActive;
		protected List<RepositoryConnection> internalConnections;

		protected LdpCacheConnection(Repository repository) {
			super(repository);
			isActive = false;
			internalConnections = new ArrayList<>();
		}

		// keep track of internal connections that have been opened
		protected RepositoryConnection getInternalConnection() throws RepositoryException {
			RepositoryConnection newConn = LdpCache.getInstance().getConnection();
			internalConnections.add(newConn);
			return newConn;
		}

		@Override
		public void close() {
			try {
				// close all internal connections together with the outer one
				for (RepositoryConnection conn : internalConnections) {
					if (conn.isOpen()) {
						conn.close();
					}
				}
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}

		@Override
		public void begin() throws RepositoryException {
			isActive = true;
		}

		@Override
		public boolean isActive() throws UnknownTransactionStateException, RepositoryException {
			return isActive;
		}

		@Override
		public void commit() throws RepositoryException {
			isActive = false;
		}

		@Override
		public void rollback() throws RepositoryException {
			isActive = false;
		}

		@Override
		public RepositoryResult<Resource> getContextIDs() throws RepositoryException {
			List<Resource> contextIDs = new ArrayList<Resource>();
			contextIDs.add(getValueFactory().createURI(LdpCache.CACHE_MODEL_URI.toString()));
			return new RepositoryResult<Resource>(
					new CloseableIteratorIteration<Resource, RepositoryException>(contextIDs.iterator()));
		}

		@Override
		// FIXME: check the contexts, skip when cache context is missing
		public RepositoryResult<Statement> getStatements(Resource subject, URI predicate, Value object,
				boolean includeInferred, Resource... contexts) throws RepositoryException {
			URI endpoint = LdpCache.getInstance().getEndpoint(subject);
			if (null != endpoint) {
				// endpoint -> LDP resource -> update (iff necessary)
				LdpClient.update(subject, endpoint);
			}
			// query the internal connection with the given parameters
			return getInternalConnection().getStatements(subject, predicate, object, includeInferred, contexts);
		}

		@Override
		// FIXME: check the contexts, skip when cache context is missing
		public void exportStatements(Resource subject, URI predicate, Value object, boolean includeInferred,
				RDFHandler handler, Resource... contexts) throws RepositoryException, RDFHandlerException {
			URI endpoint = LdpCache.getInstance().getEndpoint(subject);
			if (null != endpoint) {
				// endpoint -> LDP resource -> update (iff necessary)
				LdpClient.update(subject, endpoint);
			}
			// hand it off to the internal connection with the given parameters
			getInternalConnection().exportStatements(subject, predicate, object, includeInferred, handler, contexts);
		}

		@Override
		public Query prepareQuery(QueryLanguage ql, String query, String baseURI)
				throws RepositoryException, MalformedQueryException {
			return getInternalConnection().prepareQuery(ql, query, baseURI);
		}

		@Override
		public BooleanQuery prepareBooleanQuery(QueryLanguage ql, String query, String baseURI)
				throws RepositoryException, MalformedQueryException {
			return getInternalConnection().prepareBooleanQuery(ql, query, baseURI);
		}

		@Override
		public GraphQuery prepareGraphQuery(QueryLanguage ql, String query, String baseURI)
				throws RepositoryException, MalformedQueryException {
			return getInternalConnection().prepareGraphQuery(ql, query, baseURI);
		}

		@Override
		public TupleQuery prepareTupleQuery(QueryLanguage ql, String query, String baseURI)
				throws RepositoryException, MalformedQueryException {
			return getInternalConnection().prepareTupleQuery(ql, query, baseURI);
		}

		@Override
		public Update prepareUpdate(QueryLanguage ql, String query, String baseURI)
				throws RepositoryException, MalformedQueryException {
			return getInternalConnection().prepareUpdate(ql, query, baseURI);
		}

		@Override
		public String getNamespace(String prefix) throws RepositoryException {
			return getInternalConnection().getNamespace(prefix);
		}

		@Override
		public void setNamespace(String prefix, String name) throws RepositoryException {
			throw new UnsupportedOperationException("setNamespace() not supported");
		}

		@Override
		public void removeNamespace(String prefix) throws RepositoryException {
			throw new UnsupportedOperationException("removeNamespace() not supported");
		}

		@Override
		public RepositoryResult<Namespace> getNamespaces() throws RepositoryException {
			return getInternalConnection().getNamespaces();
		}

		@Override
		public void clearNamespaces() throws RepositoryException {
			getInternalConnection().clearNamespaces();
		}

		@Override
		// FIXME: check the contexts, skip when cache context is missing
		public long size(Resource... contexts) throws RepositoryException {
			return getInternalConnection().size(contexts);
		}

		@Override
		protected void addWithoutCommit(Resource subject, URI predicate, Value object, Resource... contexts)
				throws RepositoryException {
			throw new UnsupportedOperationException("addWithoutCommit() not supported");
		}

		@Override
		protected void removeWithoutCommit(Resource subject, URI predicate, Value object, Resource... contexts)
				throws RepositoryException {
			throw new UnsupportedOperationException("removeWithoutCommit() not supported");
		}
	}
}
