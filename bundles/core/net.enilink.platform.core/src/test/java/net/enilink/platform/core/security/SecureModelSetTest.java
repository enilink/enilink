/*******************************************************************************
 * Copyright (c) 2009, 2010 Fraunhofer IWU and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Fraunhofer IWU - initial API and implementation
 *******************************************************************************/
package net.enilink.platform.core.security;

import com.google.inject.Guice;
import net.enilink.komma.core.*;
import net.enilink.komma.model.*;
import net.enilink.platform.security.auth.AuthModule;
import net.enilink.vocab.acl.Authorization;
import net.enilink.vocab.acl.ENILINKACL;
import net.enilink.vocab.acl.WEBACL;
import net.enilink.vocab.foaf.Agent;
import net.enilink.vocab.owl.Class;
import net.enilink.vocab.owl.OWL;
import net.enilink.vocab.owl.OwlProperty;
import net.enilink.vocab.owl.Restriction;
import net.enilink.vocab.rdf.RDF;
import net.enilink.vocab.rdfs.RDFS;
import net.enilink.vocab.rdfs.Resource;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import javax.security.auth.Subject;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

public class SecureModelSetTest {
	final static URI alice = SecurityUtil.usernameToUri("alice");
	final static URI bob = SecurityUtil.usernameToUri("bob");
	final static URI carl = SecurityUtil.usernameToUri("carl");

	IModelSet modelSet;

	IModel model1, model2;

	@Before
	public void beforeTest() {
		KommaModule module = ModelPlugin.createModelSetModule(getClass()
				.getClassLoader());
		// add authentication classes
		module.includeModule(new AuthModule());

		// add security classes
		module.addBehaviour(SecureModelSetSupport.class);
		module.addConcept(ISecureEntity.class);
		module.addBehaviour(SecureEntitySupport.class);

		IModelSetFactory factory = Guice.createInjector(
				new ModelSetModule(module)).getInstance(IModelSetFactory.class);
		IGraph config = new LinkedHashGraph();
		URI msUri = URIs.createURI("test:modelSet");
		config.add(msUri, RDF.PROPERTY_TYPE, MODELS.TYPE_MODELSET);
		config.add(msUri, RDF.PROPERTY_TYPE,
				MODELS.NAMESPACE_URI.appendLocalPart("MemoryModelSet"));
		config.add(msUri, MODELS.NAMESPACE_URI.appendLocalPart("inference"),
				false);

		modelSet = factory.createModelSet(msUri, config);

		model1 = modelSet.createModel(URIs
				.createURI("http://enilink.net/test/model1"));
		model2 = modelSet.createModel(URIs
				.createURI("http://enilink.net/test/model2"));
		createAcl(modelSet.getMetaDataManager(), alice, model1.getURI(),
				WEBACL.MODE_READ);
		createAcl(modelSet.getMetaDataManager(), alice, model2.getURI(),
				WEBACL.MODE_WRITE);
		createAcl(modelSet.getMetaDataManager(), bob, model1.getURI(),
				WEBACL.MODE_WRITE);
		createAcl(modelSet.getMetaDataManager(), bob, model1.getURI(),
				WEBACL.MODE_READ);
		createAcl(modelSet.getMetaDataManager(), carl, model1.getURI(),
				ENILINKACL.MODE_WRITERESTRICTED);
		createAcl(modelSet.getMetaDataManager(), carl, model2.getURI(),
				WEBACL.MODE_WRITE);
	}

	@After
	public void afterTest() {
		modelSet.dispose();
	}

	protected void addData(IModel model) {
		IEntityManager em = model.getManager();
		try {
			em.getTransaction().begin();

			URI name = URIs.createURI("class:"
					+ BlankNode.generateId().substring(2));
			Class c = em.createNamed(name, Class.class);
			c.setRdfsLabel(name.toString());
			Restriction r = em.create(Restriction.class);
			r.setOwlOnProperty(em.find(RDFS.PROPERTY_LABEL, OwlProperty.class));
			r.setOwlMaxCardinality(BigInteger.valueOf(1));
			c.getRdfsSubClassOf().add(r);

			// add an owner statement for the class
			em.add(new Statement(c, WEBACL.PROPERTY_OWNER, SecurityUtil
					.getUser()));

			em.getTransaction().commit();
		} catch (Exception e) {
			if (em.getTransaction().isActive()) {
				em.getTransaction().rollback();
			}
			throw e;
		}
	}

	protected void exceptionExpected() {
		Assert.fail("Security exception expected.");
	}

	@Test
	public void testModelAcls() {
		try {
			// adding data without user should fail
			addData(model1);
			exceptionExpected();
		} catch (KommaException ignored) {
		}

		try {
			// adding data as alice to model1 should also fail
			Subject.callAs(SecurityUtil.subjectForUser(alice),
					() -> {
						addData(model1);
						return null;
					});
			exceptionExpected();
		} catch (CompletionException ignored) {
		}

		// adding data as alice to model2 should work
		Subject.callAs(SecurityUtil.subjectForUser(alice),
				() -> {
					addData(model2);
					return null;
				});

		// adding data as bob to model1 should work
		Subject.callAs(SecurityUtil.subjectForUser(bob),
				() -> {
					addData(model1);
					return null;
				});

		Assert.assertFalse("The model " + model1
				+ " should not be readable by " + bob, model1.getManager()
				.hasMatch(null, WEBACL.PROPERTY_OWNER, bob));
		Subject.callAs(SecurityUtil.subjectForUser(alice),
				() -> {
					Assert.assertTrue(
							"The model " + model1
									+ " should be readable by " + alice,
							model1.getManager().hasMatch(null,
									WEBACL.PROPERTY_OWNER, bob));
					return null;
				});
	}

	@Test
	public void testLocalAcls() {
		Subject.callAs(SecurityUtil.subjectForUser(carl),
				() -> {
					// adding data as carl to model2 should work
					addData(model2);
					// adding data as carl to model1 should fail
					try {
						addData(model1);
						exceptionExpected();
					} catch (KommaException ignored) {
					}
					return null;
				});

		final URI carlsClass = URIs.createURI("resource:carls-class");
		final URI carlsRestriction = URIs
				.createURI("resource:carls-restriction");
		final Authorization[] restrictionAcl = {null};
		Subject.callAs(SecurityUtil.SYSTEM_USER_SUBJECT,
				() -> {
					model1.getManager()
							.createNamed(carlsClass, Class.class);
					// carl may write the carlsClass resource
					createAcl(model1.getManager(), carl, carlsClass,
							WEBACL.MODE_WRITE);

					model1.getManager().createNamed(carlsRestriction,
							Restriction.class);
					// carl may write any restriction resource
					restrictionAcl[0] = createAclForClass(
							model1.getManager(), carl,
							OWL.TYPE_RESTRICTION, WEBACL.MODE_WRITE);
					return null;
				});

		Subject.callAs(SecurityUtil.subjectForUser(carl),
				() -> {
					model1.getManager().add(
							new Statement(carlsClass, RDFS.PROPERTY_LABEL,
									"Carl's class"));
					Assert.assertTrue(model1.getManager()
							.hasMatch(carlsClass, RDFS.PROPERTY_LABEL,
									"Carl's class"));

					model1.getManager().add(
							new Statement(carlsRestriction,
									RDFS.PROPERTY_LABEL,
									"Carl's restriction"));
					Assert.assertTrue(model1.getManager().hasMatch(
							carlsRestriction, RDFS.PROPERTY_LABEL,
							"Carl's restriction"));

					try {
						// adding an arbitrary resource should fail
						model1.getManager()
								.add(new Statement(
										URIs.createURI("resource:someResource"),
										RDFS.PROPERTY_LABEL,
										"Some resource"));
						exceptionExpected();
					} catch (KommaException ignored) {
					}
					return null;
				});

		Subject.callAs(SecurityUtil.SYSTEM_USER_SUBJECT,
				() -> {
					// remove carl's write access to restrictions
					model1.getManager().removeRecursive(restrictionAcl[0],
							true);
					return null;
				});

		Subject.callAs(SecurityUtil.subjectForUser(carl),
				() -> {
					IEntityManager em = model1.getManager();
					try {
						// adding an addition label to carlsRestriction
						// should fail
						em.add(new Statement(carlsRestriction,
								RDFS.PROPERTY_LABEL,
								"Other label for Carl's restriction"));
						exceptionExpected();
					} catch (KommaException ignored) {
					}

					// adding a new restriction to carlsClass should work
					// since only blank nodes are newly created
					try {
						em.getTransaction().begin();

						Restriction r = em.create(Restriction.class);
						r.setOwlOnProperty(em.find(RDFS.PROPERTY_LABEL,
								OwlProperty.class));
						r.setOwlMaxCardinality(BigInteger.valueOf(1));
						em.find(carlsClass, Class.class)
								.getRdfsSubClassOf().add(r);

						em.getTransaction().commit();
					} catch (Exception e) {
						if (em.getTransaction().isActive()) {
							em.getTransaction().rollback();
						}
						throw e;
					}

					// creating a detached restriction should fail
					try {
						em.getTransaction().begin();

						Restriction r = em.create(Restriction.class);
						r.setOwlOnProperty(em.find(RDFS.PROPERTY_LABEL,
								OwlProperty.class));
						r.setOwlMaxCardinality(BigInteger.valueOf(1));

						em.getTransaction().commit();
						exceptionExpected();
					} catch (Exception e) {
						em.getTransaction().rollback();
					}

					// creating a detached restriction outside of a
					// transaction should also fail
					try {
						em.create(Restriction.class);
						exceptionExpected();
					} catch (Exception ignored) {
					}

					return null;
				});

	}

	List<IReference> addList(IEntityManager em, IReference subject,
	                         IReference property) {
		List<IReference> items = new ArrayList<>();
		IReference list = em.createReference();
		items.add(list);
		em.add(new Statement(subject, property, list));
		for (int i = 0; i < 5; i++) {
			IReference next = em.createReference();
			items.add(next);
			em.add(new Statement(list, RDF.PROPERTY_FIRST, URIs
					.createURI("some:value-" + i)));
			em.add(new Statement(list, RDF.PROPERTY_REST, next));
			list = next;
		}
		return items;
	}

	@Test
	public void testLocalAclsWithSharedBNodes() {
		final URI bobsClass = URIs.createURI("resource:bobs-class");
		final URI carlsClass = URIs.createURI("resource:carls-class");
		final List<IReference> bobsList = new ArrayList<>();
		final List<IReference> carlsList = new ArrayList<>();
		Subject.callAs(SecurityUtil.subjectForUser(bob),
				() -> {
					IEntityManager em = model1.getManager();

					// create Bob's class
					em.createNamed(bobsClass, Class.class);
					bobsList.addAll(addList(em, bobsClass,
							URIs.createURI("some:prop")));

					// create Carl's class
					em.createNamed(carlsClass, Class.class);
					// carl may write the carlsClass resource
					createAcl(model1.getManager(), carl, carlsClass,
							WEBACL.MODE_WRITE);

					return null;
				});

		Subject.callAs(SecurityUtil.subjectForUser(carl),
				() -> {
					IEntityManager em = model1.getManager();

					// add a list of items to Carl's class
					carlsList.addAll(addList(em, carlsClass,
							URIs.createURI("some:prop")));

					// appending an item of bob should fail
					IReference lastItem = carlsList.getLast();
					IReference bobsItem = bobsList.get(3);
					try {
						em.add(new Statement(lastItem, RDF.PROPERTY_REST,
								bobsItem));
						exceptionExpected();
					} catch (Exception ignored) {
					}

					// deleting an item of bob should fail
					try {
						em.remove(bobsItem);
						exceptionExpected();
					} catch (Exception ignored) {
					}
					return null;
				});

		Subject.callAs(SecurityUtil.subjectForUser(bob),
				() -> {
					// carl may write the bobsClass resource
					createAcl(model1.getManager(), carl, bobsClass,
							WEBACL.MODE_WRITE);
					return null;
				});

		Subject.callAs(SecurityUtil.subjectForUser(carl),
				() -> {
					IEntityManager em = model1.getManager();
					// appending an item of bob should work now, since ACLs
					// have been added by bob
					IReference lastItem = carlsList.getLast();
					IReference bobsItem = bobsList.get(3);
					em.add(new Statement(lastItem, RDF.PROPERTY_REST,
							bobsItem));

					// deleting an item of bob should work now
					em.remove(bobsItem);
					return null;
				});
	}

	@Test
	public void testControlAcls() {
		final URI bobsClass = URIs.createURI("resource:bobs-class");
		final URI carlsClass = URIs.createURI("resource:carls-class");
		Subject.callAs(SecurityUtil.subjectForUser(bob),
				() -> {
					IEntityManager em = model1.getManager();

					// create Bob's class
					em.createNamed(bobsClass, Class.class);

					// create Carl's class
					em.createNamed(carlsClass, Class.class);
					// carl may control the carlsClass resource
					createAcl(model1.getManager(), carl, carlsClass,
							WEBACL.MODE_CONTROL);

					return null;
				});

		Subject.callAs(SecurityUtil.subjectForUser(carl),
				() -> {
					IEntityManager em = model1.getManager();

					// add ACL to bobsClass should fail
					try {
						em.getTransaction().begin();
						createAcl(em, alice, bobsClass, WEBACL.MODE_WRITE);
						em.getTransaction().commit();
						exceptionExpected();
					} catch (Exception e) {
						// ignore
					} finally {
						if (em.getTransaction().isActive()) {
							em.getTransaction().rollback();
						}
					}

					// add ACL to carlsClass should work
					try {
						em.getTransaction().begin();
						createAcl(em, alice, carlsClass, WEBACL.MODE_WRITE);
						em.getTransaction().commit();
					} finally {
						if (em.getTransaction().isActive()) {
							em.getTransaction().rollback();
						}
					}
					return null;
				});
	}

	Authorization createAclForClass(IEntityManager em, IReference agent,
	                                IReference targetClass, IReference mode) {
		Authorization auth = em.create(Authorization.class);
		auth.setAclAccessToClass(em.find(targetClass, Class.class));
		auth.setAclAgent(em.find(agent, Agent.class));
		auth.getAclMode()
				.add(em.find(mode, net.enilink.vocab.rdfs.Class.class));
		return auth;
	}

	Authorization createAcl(IEntityManager em, IReference agent,
	                        IReference target, IReference mode) {
		Authorization auth = em.create(Authorization.class);
		auth.setAclAccessTo(em.find(target, Resource.class));
		auth.setAclAgent(em.find(agent, Agent.class));
		auth.getAclMode()
				.add(em.find(mode, net.enilink.vocab.rdfs.Class.class));
		return auth;
	}
}