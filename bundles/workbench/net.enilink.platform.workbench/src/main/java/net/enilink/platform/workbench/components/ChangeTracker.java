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
import java.util.stream.Collectors;

@Component
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
	protected volatile boolean ignoreChanges = false;

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
				if (ignoreChanges) {
					return;
				}
				Map<IReference, List<IDataChange>> changesByModel = list.stream()
						.filter(change -> change instanceof IStatementChange)
						.filter(change -> {
							IReference ctx = ((IStatementChange) change).getStatement().getContext();
							return !(ctx == null || ctx.equals(metaDataContext)
									|| ctx.toString().startsWith("enilink:audit:"));
						}).collect(Collectors.groupingBy(change ->
								((IStatementChange) change).getStatement().getContext()
						));
				changesByModel.entrySet().stream().forEach(entry -> {
					XMLGregorianCalendar now = dtFactory.newXMLGregorianCalendar(new GregorianCalendar());
					IReference modelRef = entry.getKey();
					List<IDataChange> modelChanges = new ArrayList<>(entry.getValue());
					Collections.sort(modelChanges, Comparator.comparing(v -> ((IStatementChange) v).isAdd() ? 1 : 0));

					UUID uuid = UUID.randomUUID();
					URI changeUri = URIs.createURI("enilink:change:" + uuid.toString());
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
					Optional.ofNullable(SecurityUtil.getUser()).ifPresent(user -> {
						changeDescription.add(new Statement(changeUri, PROPERTY_AGENT, user));
					});
					changeDescription.add(new Statement(changeUri, PROPERTY_DATE, now));

					Subject.doAs(SecurityUtil.SYSTEM_USER_SUBJECT, (PrivilegedAction<Object>) () -> {
						URI auditModelUri = URIs.createURI("enilink:audit:" + modelRef.toString());
						IModel auditModel = modelSet.getModel(auditModelUri, false);
						if (auditModel == null) {
							auditModel = modelSet.createModel(auditModelUri);
							auditModel.setLoaded(true);
						}

						auditModel.getManager().add(changeDescription);
						return null;
					});
				});
			};
			modelSet.getDataChangeSupport().setDefaultEnabled(true);
			modelSet.getDataChangeSupport().setDefaultMode(IDataChangeSupport.Mode.EXPAND_WILDCARDS_ON_REMOVAL);
			modelSet.getDataChangeSupport().addChangeListener(changeListener);
		} finally {
			modelSet.getUnitOfWork().end();
		}
	}

	@Deactivate
	protected void deactivate() {
		if (changeListener != null) {
			modelSet.getDataChangeSupport().removeChangeListener(changeListener);
		}
	}
}