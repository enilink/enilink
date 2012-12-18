package net.enilink.core.security;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.util.Collections;
import java.util.Set;

import javax.security.auth.Subject;

import net.enilink.auth.UserPrincipal;

import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;

import net.enilink.komma.core.URI;
import net.enilink.komma.core.URIImpl;

/**
 * Helper class to get current user from the execution context.
 */
public class SecurityUtil {
	public static final URI UNKNOWN_USER = URIImpl
			.createURI("urn:enilink:anonymous");

	public static final URI SYSTEM_USER = URIImpl
			.createURI("urn:enilink:system");

	public static final Subject SYSTEM_USER_SUBJECT = new Subject(true,
			Collections.singleton(new UserPrincipal(SYSTEM_USER)),
			Collections.emptySet(), Collections.emptySet());

	private static final QualifiedName JOB_CONTEXT = new QualifiedName(
			"net.enilink.core.security", "Context");

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
	 * Returns the current user or <code>null</code>.
	 * 
	 * @return The id of the current user or <code>null</code>.
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
			Set<UserPrincipal> principals = s
					.getPrincipals(UserPrincipal.class);
			if (!principals.isEmpty()) {
				return principals.iterator().next().getId();
			}
		}
		return UNKNOWN_USER;
	}
}
