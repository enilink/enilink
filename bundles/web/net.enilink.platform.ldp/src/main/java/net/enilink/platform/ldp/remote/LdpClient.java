package net.enilink.platform.ldp.remote;

import net.enilink.platform.ldp.remote.LdpCache.LdpCacheConnection;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.rio.*;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

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
	 * @param resource The resource to check.
	 * @return True if the resource needs to be updated (doesn't exist in the
	 * cache yet or is out-of-date), false if it is up-to-date.
	 */
	public static boolean needsUpdate(Resource resource) throws Exception {
		return needsUpdate(resource, LdpCache.getInstance().getEndpoint(resource));
	}

	/**
	 * Checks if the given resource representation is up-to-date by issuing a
	 * HEAD request for it and comparing the response ETag with the cached one.
	 *
	 * @param endpoint The endpoint (short-circuit when already determined).
	 * @see #needsUpdate(Resource)
	 */
	public static boolean needsUpdate(Resource resource, IRI endpoint) throws Exception {
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

		CloseableHttpClient httpClient = createHttpClient();
		HttpHead headRequest = new HttpHead(uri.toString());
		try {
			CloseableHttpResponse headResponse = httpClient.execute(headRequest);
			logger.info("HEAD response status={} content-type={}", headResponse.getStatusLine().getStatusCode(),
					headResponse.getLastHeader("Content-Type"));
			Header eTagHeader = headResponse.getLastHeader("ETag");
			headResponse.close();
			String newETag = eTagHeader != null ? eTagHeader.getValue() : "-UNSET-";
			String cachedETag = getETag(uri);
			logger.info("ETag header value: {} vs. cached ETag: {}", newETag, cachedETag);
			return !newETag.equals(cachedETag);
		} catch (Throwable t) {
			throw t;
			// FIXME: avoid continuous retries with unreliable connections
			// maybe use some exponential-back-off strategy
		} finally {
			try {
				httpClient.close();
			} catch (IOException ignored) {
			}
		}
	}

	/**
	 * Update the given resource, iff necessary (calls {@link #needsUpdate} to
	 * check).
	 *
	 * @param resource The resource to check/update.
	 * @return True when the resource has been successfully updated, false if it
	 * was up-to-date or the update failed.
	 */
	public static boolean update(Resource resource) throws Exception {
		return update(resource, LdpCache.getInstance().getEndpoint(resource));
	}

	/**
	 * Update the given resource, iff necessary (calls {@link #needsUpdate} to
	 * check).
	 *
	 * @param endpoint The endpoint (short-circuit when already determined).
	 * @see #update(Resource)
	 */
	public static boolean update(Resource resource, IRI endpoint) throws Exception {
		if (!(resource instanceof IRI)) {
			return false;
		}
		IRI uri = (IRI) resource;

		if (!needsUpdate(resource, endpoint)) {
			return false;
		}

		List<Statement> remoteStmts = acquireRemoteStatements(uri);
		if (!remoteStmts.isEmpty()) {
			logger.trace("getting LdpCache connection...");
			LdpCacheConnection conn = LdpCache.getInstance().getConnection();
			try {
				// start by removing the existing statements w/ subject uri
				// do this in a separate transaction
				// FIXME: since statements with subjects other then the
				// request uri are accepted below, deal with deleting them
				boolean wasActive = conn.isActive();
				if (!wasActive) conn.begin();
				try {
					// maybe include the endpoint as context as well?
					// (see below)
					// FIXME: delete sub-resources? (see above)
					conn.remove(uri, null, null, LdpCache.CACHE_MODEL_IRI);
					if (!wasActive && conn.isActive()) conn.commit();
				} finally {
					if (!wasActive && conn.isActive())
						conn.rollback();
				}

				// now add all statements from the LDP endpoint
				// do this in a separate transaction as well
				wasActive = conn.isActive();
				if (!wasActive) conn.begin();
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
					if (!wasActive && conn.isActive()) {
						conn.commit();
					}
				} finally {
					if (!wasActive && conn.isActive()) {
						conn.rollback();
					}
				}
			} finally {
				conn.close();
			}

			logger.trace("added {} statements for '{}'", remoteStmts.size(), uri);
			return true;
		}
		return false;
	}

	/**
	 * Returns the value of any cached ETag for the given IRI, or null.
	 *
	 * @param uri The IRI to get the cached ETag for.
	 * @return The cached ETag value or null if no ETag is known yet.
	 * @throws RepositoryException
	 */
	protected static String getETag(IRI uri) {
		if (eTagCache.containsKey(uri)) {
			return eTagCache.get(uri);
		}

		try {
			LdpCacheConnection conn = LdpCache.getInstance().getConnection();
			try {
				String eTag = null;
				List<Statement> stmts = conn.getStatements(uri, PROPERTY_ETAG, null, false,
						LdpCache.CACHE_MODEL_IRI);
				//while (stmts.hasNext()) {
				for (Statement stmt : stmts) {
					//Statement stmt = stmts.next();
					eTag = stmt.getObject().stringValue();
				}
				//stmts.close();
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
	 * @param uri The uri to request as LDP resource.
	 * @return The list of statements from the LDP server for the requested LDP
	 * resource.
	 */
	// TODO: check LDP requirements wrt. server response etc.
	public static List<Statement> acquireRemoteStatements(final IRI uri) {
		final List<Statement> stmts = new ArrayList<Statement>();

		CloseableHttpClient httpClient = createHttpClient();
		HttpGet getRequest = new HttpGet(uri.toString());
		try {
			CloseableHttpResponse getResponse = httpClient.execute(getRequest);
			String responseMimeType = getResponse.getLastHeader("Content-Type").getValue();
			logger.info("GET '{}' response status={} content-type={}", uri, getResponse.getStatusLine().getStatusCode(),
					responseMimeType);
			InputStream resultStream = getResponse.getEntity().getContent();
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
				resultStream.close();
				getResponse.close();
			}

			if (!stmts.isEmpty()) {
				Header eTagHeader = getResponse.getLastHeader("ETag");
				String eTag = eTagHeader != null ? eTagHeader.getValue() : "-UNSET-";
				logger.trace("GET '{}' ETag header value: {}", uri, eTag);
				eTagCache.put(uri, eTag);
				stmts.add(vf.createStatement(uri, PROPERTY_ETAG, vf.createLiteral(eTag)));
			}
		} catch (Throwable t) {
			t.printStackTrace();
		} finally {
			try {
				httpClient.close();
			} catch (IOException ignored) {
			}
		}
		return stmts;
	}

	/**
	 * Create a new HttpClient instance that prefers text/turtle content.
	 */
	protected static CloseableHttpClient createHttpClient() {
		Header acceptTurtleHeader = new BasicHeader(HttpHeaders.ACCEPT, "text/turtle");
		return HttpClients.custom().setDefaultHeaders(Arrays.asList(acceptTurtleHeader)).build();
	}
}
