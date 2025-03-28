package net.enilink.platform.workbench.components;

import net.enilink.komma.core.*;
import net.enilink.komma.dm.change.IDataChange;
import net.enilink.komma.dm.change.IDataChangeListener;
import net.enilink.komma.dm.change.IDataChangeSupport;
import net.enilink.komma.dm.change.IStatementChange;
import net.enilink.komma.model.IModel;
import net.enilink.komma.model.IModelSet;
import net.enilink.komma.model.concepts.ModelSet;
import net.enilink.platform.core.security.SecurityUtil;
import net.enilink.vocab.komma.KOMMA;
import net.enilink.vocab.rdf.RDF;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.Subject;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.security.PrivilegedAction;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component(immediate = true)
public class ChangeTracker {
	static final Logger log = LoggerFactory.getLogger(ChangeTracker.class);

	static final URI TYPE_CHANGEDESCRIPTION = KOMMA.NAMESPACE_URI.appendLocalPart("ChangeDescription");
	static final URI PROPERTY_ADDSTATEMENT = KOMMA.NAMESPACE_URI.appendLocalPart("added");
	static final URI PROPERTY_REMOVESTATEMENT = KOMMA.NAMESPACE_URI.appendLocalPart("removed");
	static final URI PROPERTY_AGENT = KOMMA.NAMESPACE_URI.appendLocalPart("agent");
	static final URI PROPERTY_DATE = URIs.createURI("http://purl.org/dc/terms/").appendLocalPart("date");

	protected IModelSet modelSet;
	protected IDataChangeListener changeListener;
	protected DatatypeFactory dtFactory;

	protected ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

	protected Future<?> future;

	protected final Map<IReference, Map<URI, List<IDataChange>>> changesByModelAndUser = new HashMap<>();

	@Reference
	protected void setModelSet(IModelSet modelSet) {
		this.modelSet = modelSet;
	}

	@Activate
	protected void activate() throws DatatypeConfigurationException {
		dtFactory = DatatypeFactory.newInstance();
		try {
			modelSet.getUnitOfWork().begin();

			URI metaDataContext = ((ModelSet) modelSet).getMetaDataContext();
			changeListener = list -> {
				URI user = SecurityUtil.getUser();
				synchronized (changesByModelAndUser) {
					list.stream()
							.filter(change -> change instanceof IStatementChange)
							.filter(change -> {
								IReference ctx = ((IStatementChange) change).getStatement().getContext();
								return !(ctx == null || ctx.equals(metaDataContext)
										|| ctx.toString().startsWith("enilink:audit:"));
							}).forEach(change -> {
						IReference ctx = ((IStatementChange) change).getStatement().getContext();
						changesByModelAndUser.compute(ctx, (ctxKey, map) -> {
							if (map == null) {
								map = new HashMap<>();
							}
							map.compute(user, (userKey, l) -> {
								if (l == null) {
									l = new ArrayList<>();
								}
								l.add(change);
								return l;
							});
							return map;
						});
					});

					if (future == null) {
						// collect all changes within one second
						future = executor.schedule(this::commitChanges, 1000, TimeUnit.MILLISECONDS);
					}
				}
			};
			modelSet.getDataChangeSupport().setDefaultEnabled(true);
			modelSet.getDataChangeSupport().setDefaultMode(IDataChangeSupport.Mode.EXPAND_WILDCARDS_ON_REMOVAL);
			modelSet.getDataChangeSupport().addChangeListener(changeListener);
		} finally {
			modelSet.getUnitOfWork().end();
		}
	}

	protected void commitChanges() {
		Map<IReference, Map<URI, List<IDataChange>>> localChanges;
		synchronized (changesByModelAndUser) {
			localChanges = new HashMap<>(changesByModelAndUser);
			changesByModelAndUser.clear();
			future = null;
		}
		localChanges.forEach((modelRef, value1) -> value1.forEach((user, value) -> {
			XMLGregorianCalendar now = dtFactory.newXMLGregorianCalendar(new GregorianCalendar());
			List<IDataChange> modelChanges = new ArrayList<>(value);
			modelChanges.sort(Comparator.comparing(v -> ((IStatementChange) v).isAdd() ? 1 : 0));

			UUID uuid = UUID.randomUUID();
			URI changeUri = URIs.createURI("enilink:change:" + uuid);
			List<IStatement> changeDescription = new ArrayList<>();
			modelChanges.forEach(change -> {
				IStatement stmt = ((IStatementChange) change).getStatement();
				boolean isAdd = ((IStatementChange) change).isAdd();
				IReference stmtRef = new BlankNode(BlankNode.generateId("stmt-"));
				changeDescription.add(new Statement(changeUri, isAdd ? PROPERTY_ADDSTATEMENT : PROPERTY_REMOVESTATEMENT, stmtRef));
				changeDescription.add(new Statement(stmtRef, RDF.PROPERTY_SUBJECT, stmt.getSubject()));
				changeDescription.add(new Statement(stmtRef, RDF.PROPERTY_PREDICATE, stmt.getPredicate()));
				changeDescription.add(new Statement(stmtRef, RDF.PROPERTY_OBJECT, stmt.getObject()));
			});
			changeDescription.add(new Statement(changeUri, RDF.PROPERTY_TYPE, TYPE_CHANGEDESCRIPTION));
			changeDescription.add(new Statement(changeUri, PROPERTY_AGENT, user));
			changeDescription.add(new Statement(changeUri, PROPERTY_DATE, now));

			Subject.doAs(SecurityUtil.SYSTEM_USER_SUBJECT, (PrivilegedAction<Object>) () -> {
				URI auditModelUri = URIs.createURI("enilink:audit:" + modelRef.toString());
				try {
					modelSet.getUnitOfWork().begin();

					IModel auditModel = modelSet.getModel(auditModelUri, false);
					if (auditModel == null) {
						auditModel = modelSet.createModel(auditModelUri);
						auditModel.setLoaded(true);
					}

					auditModel.getManager().add(changeDescription);
				} finally {
					modelSet.getUnitOfWork().end();
				}
				return null;
			});
		}));
	}

	@Deactivate
	protected void deactivate() {
		if (changeListener != null) {
			modelSet.getDataChangeSupport().removeChangeListener(changeListener);
		}
	}
}