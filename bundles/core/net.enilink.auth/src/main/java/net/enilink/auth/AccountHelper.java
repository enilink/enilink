package net.enilink.auth;

import java.security.MessageDigest;
import java.security.Principal;
import java.security.acl.Group;
import java.util.ArrayList;
import java.util.List;

import javax.security.auth.Subject;

import net.enilink.commons.iterator.IExtendedIterator;
import net.enilink.commons.iterator.IMap;
import net.enilink.commons.iterator.WrappedIterator;
import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.IEntityManager;
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
	 * @return The user resource
	 * @throws IllegalArgumentException
	 *             If the user with the given id already exists in the database.
	 */
	public static synchronized IEntity createUser(IEntityManager em,
			String username) throws IllegalArgumentException {
		return createUser(em, username, null);
	}

	/**
	 * Creates a new user resources.
	 * 
	 * @param em
	 *            The entity manager to use
	 * @param username
	 *            The name of the user (nickname)
	 * @param encodedPassword
	 *            The already encoded password
	 * @return The user resource
	 * @throws IllegalArgumentException
	 *             If the user with the given id already exists in the database.
	 */
	public static synchronized IEntity createUser(IEntityManager em,
			String username, String encodedPassword)
			throws IllegalArgumentException {
		if (hasUser(em, username)) {
			throw new IllegalArgumentException(
					"A user with this name already exists.");
		}
		// create user and add nickname
		URI userId = getUserURI(username);
		IResource user = em.createNamed(userId, FOAF.TYPE_AGENT).as(
				IResource.class);
		user.addProperty(FOAF.PROPERTY_NICK, username);
		if (encodedPassword != null) {
			user.addProperty(URIs.createURI("urn:enilink:password"),
					encodedPassword);
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
			md.digest(new String(password).getBytes("UTF-8"));
			return new String(new Base64().encode(md.digest()));
		} catch (Exception e) {
			throw new UnsupportedOperationException(
					"Failed to encode the password: " + e.getMessage()
							+ " due to missing hash algorithm.");
		}
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
			if (!(principal instanceof Group || principal instanceof UserPrincipal)) {
				URI externalId = URIs.createURI("urn:jaas:principal:"
						+ principal.getClass().getName() + ":"
						+ URIs.encodeOpaquePart(principal.toString(), false));
				externalIds.add(externalId);
			}
		}
		return externalIds;
	}

	/**
	 * Returns the user's URI for the given user name.
	 * 
	 * @param username
	 *            The user's name.
	 * @return The user's URI.
	 */
	public static URI getUserURI(String username) {
		return URIs.createURI("http://enilink.net/users").appendLocalPart(
				URIs.encodeOpaquePart(username, false));
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
	public static synchronized boolean hasUser(IEntityManager em,
			String username) {
		URI userId = getUserURI(username);
		return em.createQuery("ask { ?user ?p ?o }")
				.setParameter("user", userId).getBooleanResult();
	}

	/**
	 * Checks if a user with the given username and password exists within the
	 * system.
	 * 
	 * @param em
	 *            The entity manager to use
	 * @param username
	 *            The user's name.
	 * @param username
	 *            The encoded password
	 * @return <code>true</code> if a user with the given name and password
	 *         exists, else <code>false</code>.
	 */
	public static synchronized IEntity findUser(IEntityManager em,
			String username, String encodedPassword) {
		URI userId = getUserURI(username);
		try (IExtendedIterator<IEntity> users = em
				.createQuery(
						"select ?user where { ?user <urn:enilink:password> ?password filter isIRI(?user) }")
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
	 * Associates a user ID with one ore more external IDs.
	 * 
	 * @param em
	 *            The entity manager for storing the data
	 * @param userId
	 *            The user ID
	 * @param externalIds
	 *            List of external IDs
	 */
	public static void linkExternalIds(IEntityManager em,
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
