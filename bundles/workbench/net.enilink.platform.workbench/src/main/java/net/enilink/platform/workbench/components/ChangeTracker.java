package net.enilink.platform.workbench.components;

import net.enilink.commons.util.Pair;
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
	static final URI PROPERTY_STATEMENT = KOMMA.NAMESPACE_URI.appendLocalPart("statement");
	static final URI PROPERTY_ADDED = KOMMA.NAMESPACE_URI.appendLocalPart("added");
	static final URI PROPERTY_INDEX = KOMMA.NAMESPACE_URI.appendLocalPart("index");
	static final URI PROPERTY_AGENT = KOMMA.NAMESPACE_URI.appendLocalPart("agent");
	static final URI PROPERTY_DATE = URIs.createURI("http://purl.org/dc/terms/").appendLocalPart("date");

	protected IModelSet modelSet;
	protected IDataChangeListener changeListener;
	protected DatatypeFactory dtFactory;

	protected ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

	protected Future<?> future;

	protected final Map<IReference, List<Pair<IDataChange, URI>>> changesByModel = new HashMap<>();

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
				synchronized (changesByModel) {
					list.stream()
							.filter(change -> change instanceof IStatementChange)
							.filter(change -> {
								IReference ctx = ((IStatementChange) change).getStatement().getContext();
								return !(ctx == null || ctx.equals(metaDataContext)
										|| ctx.toString().startsWith("enilink:audit:"));
							}).forEach(change -> {
						IReference ctx = ((IStatementChange) change).getStatement().getContext();
						changesByModel.computeIfAbsent(ctx, ctxKey -> new ArrayList<>()).add(new Pair<>(change, user));
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
		Map<IReference, List<Pair<IDataChange, URI>>> localChanges;
		synchronized (changesByModel) {
			localChanges = new HashMap<>(changesByModel);
			changesByModel.clear();
			future = null;
		}
		localChanges.forEach((modelRef, modelChanges) -> {
			var modelChangesIt = modelChanges.listIterator();
			while (modelChangesIt.hasNext()) {
				XMLGregorianCalendar now = dtFactory.newXMLGregorianCalendar(new GregorianCalendar());

				UUID uuid = UUID.randomUUID();
				URI changeUri = URIs.createURI("enilink:change:" + uuid);
				List<IStatement> changeDescription = new ArrayList<>();
				// add elements to a single change description as long the associated user is the same
				URI user = null;
				int index = 0;
				while (modelChangesIt.hasNext()) {
					var changeAndUser = modelChangesIt.next();
					var change = changeAndUser.getFirst();
					if (user == null || user == changeAndUser.getSecond() || user.equals(changeAndUser.getSecond())) {
						user = changeAndUser.getSecond();
						IStatement stmt = ((IStatementChange) change).getStatement();
						boolean isAdd = ((IStatementChange) change).isAdd();
						IReference stmtRef = new BlankNode(BlankNode.generateId("stmt-"));
						changeDescription.add(new Statement(changeUri, PROPERTY_STATEMENT, stmtRef));
						changeDescription.add(new Statement(stmtRef, RDF.PROPERTY_SUBJECT, stmt.getSubject()));
						changeDescription.add(new Statement(stmtRef, RDF.PROPERTY_PREDICATE, stmt.getPredicate()));
						changeDescription.add(new Statement(stmtRef, RDF.PROPERTY_OBJECT, stmt.getObject()));
						changeDescription.add(new Statement(stmtRef, PROPERTY_ADDED, isAdd));
						changeDescription.add(new Statement(stmtRef, PROPERTY_INDEX, index++));
					} else {
						// go back to previous element
						modelChangesIt.previous();
						break;
					}
				}
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
			}
		});
	}

	@Deactivate
	protected void deactivate() {
		if (changeListener != null) {
			modelSet.getDataChangeSupport().removeChangeListener(changeListener);
		}
	}
}