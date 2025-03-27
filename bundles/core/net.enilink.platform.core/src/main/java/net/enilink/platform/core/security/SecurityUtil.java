package net.enilink.platform.core.security;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.util.Collections;
import java.util.Set;

import javax.security.auth.Subject;

import net.enilink.komma.core.IEntityManager;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIs;
import net.enilink.komma.em.concepts.IResource;
import net.enilink.platform.security.auth.EnilinkPrincipal;
import net.enilink.vocab.acl.WEBACL;
import net.enilink.vocab.foaf.FOAF;
import net.enilink.vocab.foaf.Group;

import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;

/**
 * Helper class to get current user from the execution context.
 */
public class SecurityUtil {
	public static final URI UNKNOWN_USER = usernameToUri("anonymous");

	public static final URI SYSTEM_USER = usernameToUri("system");

	public static final URI USERS_MODEL = URIs.createURI("enilink:model:users");

	public static final URI ADMINISTRATORS_GROUP = URIs.createURI("enilink:group:Administrators");

	public static final Subject SYSTEM_USER_SUBJECT = subjectForUser(SYSTEM_USER);

	public static final String QUERY_ACLMODE = "prefix acl: <" + WEBACL.NAMESPACE + "> prefix foaf: <" + FOAF.NAMESPACE
			+ "> " + "select ?mode where { " //
			+ "{ ?target acl:owner ?agent . bind (acl:Control as ?mode) } union {"
			+ "{ ?acl acl:accessTo ?target } union { ?target a [ rdfs:subClassOf* ?class ] . ?acl acl:accessToClass ?class } . "
			+ "{ ?acl acl:agent [ foaf:member* ?agent ] } union { ?agent a [ rdfs:subClassOf* ?agentClass ] . ?acl acl:agentClass ?agentClass } . "
			+ "?acl acl:mode ?mode }" + //
			"}";
	
	public static final String QUERY_MEMBER = "prefix foaf: <" + FOAF.NAMESPACE
			+ "> ask where { ?group foaf:member* ?agent }";

	private static final QualifiedName JOB_CONTEXT = new QualifiedName(
			"net.enilink.platform.core.security", "Context");

	static {
		// change listener to propagate access control contexts for jobs
		Job.getJobManager().addJobChangeListener(new JobChangeAdapter() {
			@Override
			public void scheduled(IJobChangeEvent event) {
				AccessControlContext context = null;
				Job job = Job.getJobManager().currentJob();
				if (job != null) {
					context = (AccessControlContext) job
							.getProperty(JOB_CONTEXT);
				}
				if (context == null) {
					context = AccessController.getContext();
				}
				// attach context of current thread to job
				event.getJob().setProperty(JOB_CONTEXT, context);
			}
		});
	}

	/**
	 * Create a subject for the given user URI.
	 * 
	 * @param user
	 *            The user's URI.
	 * 
	 * @return A subject for the given user
	 */
	public static Subject subjectForUser(URI user) {
		return new Subject(true,
				Collections.singleton(new EnilinkPrincipal(user)),
				Collections.emptySet(), Collections.emptySet());
	}

	/**
	 * Returns a default URI for the given user name.
	 * 
	 * @param username
	 *            The user name
	 * @return A URI for the user
	 */
	public static URI usernameToUri(String username) {
		return URIs.createURI("enilink:user:" + username);
	}

	/**
	 * Returns a user name for the given user URI.
	 *
	 * @param uri
	 *            The user URI
	 * @return The user name
	 */
	public static String uriToUsername(URI uri) {
		String uriStr = uri.toString();
		return uriStr.replaceFirst("enilink:user:", "");
	}

	/**
	 * Returns the current user.
	 * 
	 * If the current user is unknown then {@link SecurityUtil#UNKNOWN_USER} is
	 * returned.
	 * 
	 * @return The id of the current user or {@link SecurityUtil#UNKNOWN_USER}.
	 */
	public static URI getUser() {
		Subject s = null;
		// try to get subject with context of current running job
		Job job = Job.getJobManager().currentJob();
		if (job != null) {
			AccessControlContext context = (AccessControlContext) job
					.getProperty(JOB_CONTEXT);
			if (context != null) {
				s = Subject.getSubject(context);
			}
		}
		if (s == null) {
			s = Subject.getSubject(AccessController.getContext());
		}
		if (s != null) {
			Set<EnilinkPrincipal> principals = s
					.getPrincipals(EnilinkPrincipal.class);
			if (!principals.isEmpty()) {
				return principals.iterator().next().getId();
			}
		}
		return UNKNOWN_USER;
	}

	/**
	 * Determines if the current user has the requested type within the given
	 * entity manager.
	 * 
	 * @param em
	 *            The entity manager that contains the data about the current
	 *            user
	 * @param type
	 *            The type that should be looked up
	 * 
	 * @return <code>true</code> if the current user has the given
	 *         <code>type</code>, else <code>false</code>
	 */
	public static boolean hasType(IEntityManager em, IReference type) {
		return em.find(getUser(), IResource.class).getRdfTypes().contains(type);
	}

	/**
	 * Determines if the current user is member of a group within the given
	 * entity manager.
	 * 
	 * @param em
	 *            The entity manager that contains the data about the current
	 *            user
	 * @param group
	 *            The group that should be looked up
	 * 
	 * @return <code>true</code> if the current user is member of the given
	 *         <code>group</code>, else <code>false</code>
	 */
	public static boolean isMemberOf(IEntityManager em, IReference group) {
		URI user = getUser();
		return em.find(group, Group.class).getFoafMember().contains(user) || em.createQuery(QUERY_MEMBER)
				.setParameter("agent", user).setParameter("group", group).getBooleanResult();
	}
}
