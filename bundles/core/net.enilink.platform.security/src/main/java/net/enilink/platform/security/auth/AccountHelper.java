package net.enilink.platform.security.auth;

import java.security.MessageDigest;
import java.security.Principal;
import java.security.acl.Group;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.security.auth.Subject;

import net.enilink.commons.iterator.IExtendedIterator;
import net.enilink.commons.iterator.IMap;
import net.enilink.commons.iterator.WrappedIterator;
import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.IEntityManager;
import net.enilink.komma.core.IQuery;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.core.Statement;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.em.concepts.IResource;
import net.enilink.vocab.auth.AUTH;
import net.enilink.vocab.foaf.FOAF;

import org.apache.commons.codec.binary.Base64;

/**
 * Helper class for managing user accounts.
 */
// TODO Is it possible to use database locking here?
public class AccountHelper {
	/**
	 * Creates a new user resource.
	 * 
	 * @param em
	 *            The entity manager to use
	 * @param username
	 *            The name of the user (nickname)
	 * @param emailAddress
	 *            The user's email address
	 * @return The user resource
	 * @throws IllegalArgumentException
	 *             If the user with the given id already exists in the database.
	 */
	public static synchronized IEntity createUser(IEntityManager em,
			String username, String emailAddress)
			throws IllegalArgumentException {
		return createUser(em, username, emailAddress, null);
	}

	/**
	 * Creates a new user resources.
	 * 
	 * @param em
	 *            The entity manager to use
	 * @param username
	 *            The name of the user (nickname)
	 * @param emailAddress
	 *            The user's email address
	 * @param encodedPassword
	 *            The already encoded password
	 * @return The user resource
	 * @throws IllegalArgumentException
	 *             If the user with the given id already exists in the database.
	 */
	public static synchronized IEntity createUser(IEntityManager em,
			String username, String emailAddress, String encodedPassword)
			throws IllegalArgumentException {
		if (hasUserWithName(em, username)) {
			throw new IllegalArgumentException(
					"A user with this name already exists.");
		}
		if (emailAddress != null && hasUserWithEmail(em, emailAddress)) {
			throw new IllegalArgumentException(
					"A user with this email address already exists.");
		}
		// create user and add nickname
		URI userId = getUserURI(username);
		IResource user = em.createNamed(userId, FOAF.TYPE_AGENT).as(
				IResource.class);
		user.addProperty(FOAF.PROPERTY_NICK, username);
		if (emailAddress != null) {
			user.addProperty(FOAF.PROPERTY_MBOX, getMailboxURI(emailAddress));
		}
		if (encodedPassword != null) {
			user.addProperty(AUTH.PROPERTY_PASSWORD, encodedPassword);
		}
		return user;
	}

	/**
	 * Encode a password for secure storage within a persistent database,
	 * sessions, etc.
	 * 
	 * @param password
	 *            The password to encode
	 * @return A password hash generated with SHA-1 or a comparable algorithm
	 */
	public static String encodePassword(String password) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			byte[] digest = md.digest(password.getBytes("UTF-8"));
			return new String(new Base64().encode(digest));
		} catch (Exception e) {
			throw new UnsupportedOperationException(
					"Failed to encode the password: " + e.getMessage()
							+ " due to missing hash algorithm.");
		}
	}

	/**
	 * Checks if a user with the given username and password exists within the
	 * system.
	 * 
	 * @param em
	 *            The entity manager to use
	 * @param username
	 *            The user's name.
	 * @param encodedPassword
	 *            The encoded password
	 * @return The user entity or else <code>null</code> if the user was not
	 *         found.
	 */
	public static synchronized IEntity findUser(IEntityManager em,
			String username, String encodedPassword) {
		URI userId = getUserURI(username);
		try (IExtendedIterator<IEntity> users = em
				.createQuery(
						"select ?user { ?user ?property ?password filter isIRI(?user) }")
				.setParameter("property", AUTH.PROPERTY_PASSWORD)
				.setParameter("user", userId)
				.setParameter("password", encodedPassword)
				.evaluate(IEntity.class)) {
			if (users.hasNext()) {
				return users.next();
			}

		}
		return null;
	}

	/**
	 * Checks if a user with the given external IDs exists within the system.
	 * 
	 * @param em
	 *            The entity manager to use
	 * @param externalIds
	 *            The user's external IDs.
	 * @return The user entity or else <code>null</code> if the user was not
	 *         found.
	 */
	public static synchronized IEntity findUser(IEntityManager em,
			List<URI> externalIds) {
		StringBuilder querySb = new StringBuilder("select ?user where {\n");
		for (int i = 0; i < externalIds.size(); i++) {
			querySb.append("\t{ ?user ?externalIdProp ?id").append(i)
					.append(" }\n");
			if (i < externalIds.size() - 1) {
				querySb.append("\tunion\n");
			}
		}
		querySb.append("\tfilter isIRI(?user)\n");
		querySb.append("} limit 1");

		IQuery<?> query = em.createQuery(querySb.toString());
		int i = 0;
		for (Iterator<URI> it = externalIds.iterator(); it.hasNext(); i++) {
			query.setParameter("id" + i, it.next());
		}
		query.setParameter("externalIdProp", AUTH.PROPERTY_EXTERNALID);
		IExtendedIterator<IEntity> result = query.evaluate(IEntity.class);
		if (result.hasNext()) {
			return result.next();
		}
		return null;
	}

	/**
	 * Returns external IDs from the given subject.
	 * 
	 * The external IDs are constructed from OpenID, Kerberos or other
	 * principals contained in the subject.
	 * 
	 * @param subject
	 *            The subject with principals for external IDs.
	 * 
	 * @return List of extracted external IDs.
	 */
	public static List<URI> getExternalIds(Subject subject) {
		List<URI> externalIds = new ArrayList<URI>();
		for (Principal principal : subject.getPrincipals()) {
			if (!(principal instanceof Group || principal instanceof EnilinkPrincipal)) {
				URI externalId = URIs.createURI("enilink:jaas:principal:"
						+ principal.getClass().getName() + ":"
						+ URIs.encodeOpaquePart(principal.toString(), false));
				externalIds.add(externalId);
			}
		}
		return externalIds;
	}

	/**
	 * Returns a URI for an email address.
	 * 
	 * @param emailAddress
	 *            An email address
	 * @return A URI with the <tt>mailto:</tt> scheme
	 */
	public static URI getMailboxURI(String emailAddress) {
		return URIs.createURI("mailto:" + emailAddress);
	}

	/**
	 * Returns the user's URI for the given user name.
	 * 
	 * @param username
	 *            The user's name.
	 * @return The user's URI.
	 */
	public static URI getUserURI(String username) {
		return URIs.createURI("enilink:user:").appendLocalPart(
				URIs.encodeOpaquePart(username, false));
	}

	/**
	 * Checks if a user with the given -mail address already exists within the
	 * system.
	 * 
	 * @param em
	 *            The entity manager to use
	 * @param emailAddress
	 *            The user's email address
	 * @return <code>true</code> if a user with the given email address already
	 *         exists, else <code>false</code>.
	 */
	public static synchronized boolean hasUserWithEmail(IEntityManager em,
			String emailAddress) {
		URI mbox = getMailboxURI(emailAddress);
		return em
				.createQuery(
						"prefix foaf: <" + FOAF.NAMESPACE
								+ "> ask { ?user foaf:mbox ?mbox }")
				.setParameter("mbox", mbox).getBooleanResult();
	}

	/**
	 * Checks if a user with the given username already exists within the
	 * system.
	 * 
	 * @param em
	 *            The entity manager to use
	 * @param username
	 *            The user's name.
	 * @return <code>true</code> if a user with the given name already exists,
	 *         else <code>false</code>.
	 */
	public static synchronized boolean hasUserWithName(IEntityManager em,
			String username) {
		URI userId = getUserURI(username);
		return em.createQuery("ask { ?user ?p ?o }")
				.setParameter("user", userId).getBooleanResult();
	}

	/**
	 * Associates a user ID with one ore more external IDs.
	 * 
	 * @param em
	 *            The entity manager for storing the data
	 * @param userId
	 *            The user ID
	 * @param externalIds
	 *            List of external IDs
	 */
	public static synchronized void linkExternalIds(IEntityManager em,
			final IReference userId, List<URI> externalIds) {
		em.add(WrappedIterator.create(externalIds.iterator()).mapWith(
				new IMap<URI, IStatement>() {
					@Override
					public IStatement map(URI externalId) {
						return new Statement(userId, AUTH.PROPERTY_EXTERNALID,
								externalId);
					}
				}));
	}
}
