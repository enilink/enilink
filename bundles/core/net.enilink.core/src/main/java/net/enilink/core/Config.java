package net.enilink.core;

import java.util.Set;

import net.enilink.komma.core.IGraph;
import net.enilink.komma.core.ILiteral;
import net.enilink.komma.core.IReference;
import net.enilink.komma.core.KommaException;

/**
 * Configuration of the eniLINK platform.
 * 
 * This is essentially an RDF graph with the ability to convert literal values
 * to Java types and vice-versa.
 */
public interface Config extends IGraph {
	@Override
	Config filter(IReference subj, IReference pred, Object obj,
			IReference... contexts);

	/**
	 * Returns the object of an statement and converts an {@link ILiteral} into
	 * the respective Java type.
	 * 
	 * @return An instance of {@link IReference} or the Java value for an
	 *         {@link ILiteral}
	 * @throws KommaException
	 */
	Object objectInstance() throws KommaException;

	/**
	 * Returns a {@link Set} view of the objects contained in this graph where
	 * literals are converted to their respective Java types.
	 * 
	 * @see IGraph#objects()
	 */
	Set<Object> objectInstances();
}
