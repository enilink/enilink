package net.enilink.ldp.sail;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpVersion;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.HeadMethod;
import org.openrdf.model.IRI;
import org.openrdf.model.Resource;
import org.openrdf.model.Statement;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.SimpleValueFactory;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.RDFHandlerException;
import org.openrdf.rio.RDFParser;
import org.openrdf.rio.Rio;
import org.openrdf.rio.UnsupportedRDFormatException;
import org.openrdf.rio.helpers.AbstractRDFHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple LDP client on top of commons HttpClient.
 * <p>
 * Only supports GETting resources right now, no updates.
 */
public class LdpClient {

	protected final static ValueFactory vf = SimpleValueFactory.getInstance();
	public final static IRI PROPERTY_ETAG = vf.createIRI(LdpCache.CACHE_MODEL_IRI.stringValue() + "#ETag");

	protected final static Logger logger = LoggerFactory.getLogger(LdpClient.class);

	// FIXME: deploy proper caching strategy
	protected final static Map<IRI, String> eTagCache = new HashMap<IRI, String>(10000);
	protected final static Map<IRI, Long> floodBlock = new HashMap<IRI, Long>(10000);

	/**
	 * Checks if the given resource representation is up-to-date.
	 * <p>
	 * It currently does so by issuing a HEAD request for it and comparing the
	 * response ETag with the cached one.
	 * 
	 * @param resource
	 *            The resource to check.
	 * @return True if the resource needs to be updated (doesn't exist in the
	 *         cache yet or is out-of-date), false if it is up-to-date.
	 */
	public static boolean needsUpdate(Resource resource) {
		return needsUpdate(resource, LdpCache.getInstance().getEndpoint(resource));
	}

	/**
	 * Checks if the given resource representation is up-to-date by issuing a
	 * HEAD request for it and comparing the response ETag with the cached one.
	 * 
	 * @param endpoint
	 *            The endpoint (short-circuit when already determined).
	 * @see #needsUpdate(Resource)
	 */
	public static boolean needsUpdate(Resource resource, IRI endpoint) {
		// guard against blank nodes
		if (!(resource instanceof IRI)) {
			return false;
		}
		IRI uri = (IRI) resource;

		if (null == endpoint) {
			// no endpoint -> no LDP resource -> no update needed
			return false;
		}
		// for performance reasons, the validity of the endpoint is not checked

		// guard against request floods
		// FIXME: find a better way, especially with delaying subsequent
		// requests instead of letting them read old data
		long now = System.currentTimeMillis();
		if (floodBlock.containsKey(uri) && (now - floodBlock.get(uri)) < 10000) {
			logger.trace("flood prevention for '{}' now={} then={} from thread={}", uri, now, floodBlock.get(uri),
					Thread.currentThread());
			return false;
		}
		floodBlock.put(uri, now);

		HttpClient httpClient = new HttpClient();
		httpClient.getParams().setVersion(HttpVersion.HTTP_1_1);
		HeadMethod head = new HeadMethod(uri.toString());
		head.setRequestHeader("Accept", "text/turtle");
		try {
			httpClient.executeMethod(head);
			logger.info("HEAD response status={} content-type={}", head.getStatusCode(),
					head.getResponseHeader("Content-Type").getValue());
			Header eTagHeader = head.getResponseHeader("ETag");
			String newETag = eTagHeader != null ? eTagHeader.getValue() : "-UNSET-";
			String cachedETag = getETag(uri);
			logger.info("ETag header value: {} vs. cached ETag: {}", newETag, cachedETag);
			return !newETag.equals(cachedETag);
		} catch (Throwable t) {
			t.printStackTrace();
			// FIXME: avoid continuous retries with unreliable connections
			// maybe use some exponential-back-off strategy
			return false;
		}
	}

	/**
	 * Update the given resource, iff necessary (calls {@link #needsUpdate} to
	 * check).
	 * 
	 * @param resource
	 *            The resource to check/update.
	 * @return True when the resource has been successfully updated, false if it
	 *         was up-to-date or the update failed.
	 */
	public static boolean update(Resource resource) {
		return update(resource, LdpCache.getInstance().getEndpoint(resource));
	}

	/**
	 * Update the given resource, iff necessary (calls {@link #needsUpdate} to
	 * check).
	 * 
	 * @param endpoint
	 *            The endpoint (short-circuit when already determined).
	 * @see #update(Resource)
	 */
	public static boolean update(Resource resource, IRI endpoint) {
		if (!(resource instanceof IRI)) {
			return false;
		}
		IRI uri = (IRI) resource;

		if (!needsUpdate(resource, endpoint)) {
			return false;
		}
		try {
			List<Statement> remoteStmts = acquireRemoteStatements(uri);
			if (!remoteStmts.isEmpty()) {
				RepositoryConnection conn = LdpCache.getInstance().getConnection();
				try {
					// start by removing the existing statements w/ subject uri
					// do this in a separate transaction
					// FIXME: since statements with subjects other then the
					// request uri are accepted below, deal with deleting them
					conn.begin();
					try {
						// maybe include the endpoint as context as well?
						// (see below)
						// FIXME: delete sub-resources? (see above)
						conn.remove(uri, null, null, LdpCache.CACHE_MODEL_IRI);
						conn.commit();
					} finally {
						if (conn.isActive())
							conn.rollback();
					}

					// now add all statements from the LDP endpoint
					// do this in a separate transaction as well
					conn.begin();
					try {
						for (Statement remoteStmt : remoteStmts) {
							// accepts sub-resources (containers...) to the
							// requested one (use-case: sub-containers)
							// TODO: check LDP container interaction model
							if (null != remoteStmt.getSubject()
									&& remoteStmt.getSubject().stringValue().startsWith(uri.stringValue())) {
								// maybe include the endpoint as context as
								// well? (see above)
								conn.add(remoteStmt, LdpCache.CACHE_MODEL_IRI);
							}
						}
						conn.commit();
					} finally {
						if (conn.isActive())
							conn.rollback();
					}
				} finally {
					conn.close();
				}

				logger.info("added {} statements for '{}'", remoteStmts.size(), uri);
				return true;
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}

	/**
	 * Returns the value of any cached ETag for the given IRI, or null.
	 *
	 * @param uri
	 *            The IRI to get the cached ETag for.
	 * @return The cached ETag value or null if no ETag is known yet.
	 * @throws RepositoryException
	 */
	protected static String getETag(IRI uri) {
		if (eTagCache.containsKey(uri)) {
			return eTagCache.get(uri);
		}

		try {
			RepositoryConnection conn = LdpCache.getInstance().getConnection();
			try {
				String eTag = null;
				RepositoryResult<Statement> stmts = conn.getStatements(uri, PROPERTY_ETAG, null, false,
						LdpCache.CACHE_MODEL_IRI);
				while (stmts.hasNext()) {
					Statement stmt = stmts.next();
					eTag = stmt.getObject().stringValue();
				}
				stmts.close();
				logger.trace("got ETag {} for '{}'", eTag, uri);
				eTagCache.put(uri, eTag);
				return eTag;
			} finally {
				conn.close();
			}
		} catch (RepositoryException ignored) {
			return null;
		}
	}

	/**
	 * Get the resource representation from the LDP server.
	 * 
	 * @param uri
	 *            The uri to request as LDP resource.
	 * @return The list of statements from the LDP server for the requested LDP
	 *         resource.
	 */
	// TODO: check LDP requirements wrt. server response etc.
	public static List<Statement> acquireRemoteStatements(final IRI uri) {
		final List<Statement> stmts = new ArrayList<Statement>();

		HttpClient httpClient = new HttpClient();
		httpClient.getParams().setVersion(HttpVersion.HTTP_1_1);
		GetMethod get = new GetMethod(uri.toString());
		get.setRequestHeader("Accept", "text/turtle");
		try {
			httpClient.executeMethod(get);
			String responseMimeType = get.getResponseHeader("Content-Type").getValue();
			logger.info("GET '{}' response status={} content-type={}", uri, get.getStatusCode(), responseMimeType);
			InputStream resultStream = get.getResponseBodyAsStream();
			try {
				Optional<RDFFormat> responseFormat = Rio.getParserFormatForMIMEType(responseMimeType);
				if (!responseFormat.isPresent()) {
					throw new UnsupportedRDFormatException("Unsupported response MIME type: " + responseMimeType);
				}
				RDFParser parser = Rio.createParser(responseFormat.get(), vf);
				parser.setRDFHandler(new AbstractRDFHandler() {
					@Override
					public void handleStatement(Statement stmt) throws RDFHandlerException {
						stmts.add(stmt);
					}
				});
				parser.parse(resultStream, uri.toString());
			} finally {
				try {
					resultStream.close();
				} catch (IOException e) {
					throw e;
				}
			}

			if (!stmts.isEmpty()) {
				Header eTagHeader = get.getResponseHeader("ETag");
				String eTag = eTagHeader != null ? eTagHeader.getValue() : "-UNSET-";
				logger.trace("GET '{}' ETag header value: {}", uri, eTag);
				eTagCache.put(uri, eTag);
				stmts.add(vf.createStatement(uri, PROPERTY_ETAG, vf.createLiteral(eTag)));
			}
		} catch (Throwable t) {
			t.printStackTrace();
		}
		return stmts;
	}
}
