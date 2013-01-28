package net.enilink.auth;

import java.security.Principal;
import java.security.acl.Group;
import java.util.ArrayList;
import java.util.List;

import javax.security.auth.Subject;

import net.enilink.vocab.auth.AUTH;
import net.enilink.vocab.foaf.FOAF;
import net.enilink.commons.iterator.IMap;
import net.enilink.commons.iterator.WrappedIterator;
import net.enilink.komma.concepts.IResource;
import net.enilink.komma.core.IEntity;
import net.enilink.komma.core.IEntityManager;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.IStatement;
import net.enilink.komma.core.Statement;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIImpl;

/**
 * Helper class for managing user accounts.
 */
public class AccountHelper {
	/**
	 * Creates a new user resources.
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
		URI userId = getUserURI(username);
		// TODO Is it possible to use database locking here?
		if (em.createQuery("ask { ?user ?p ?o }").setParameter("user", userId)
				.getBooleanResult()) {
			throw new IllegalArgumentException(
					"A user with this name already exists.");
		}
		// create user and add nickname
		IEntity user = em.createNamed(userId, FOAF.TYPE_AGENT);
		user.as(IResource.class).addProperty(FOAF.PROPERTY_NICK, username);
		return user;
	}

	/**
	 * Returns the user's URI for the given user name.
	 * 
	 * @param username
	 *            The user's name.
	 * @return The user's URI.
	 */
	public static URI getUserURI(String username) {
		return URIImpl.createURI("http://enilink.net/users").appendLocalPart(
				URIImpl.encodeOpaquePart(username, false));
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
				URI externalId = URIImpl
						.createURI("urn:jaas:principal:"
								+ principal.getClass().getName()
								+ ":"
								+ URIImpl.encodeOpaquePart(
										principal.toString(), false));
				externalIds.add(externalId);
			}
		}
		return externalIds;
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
