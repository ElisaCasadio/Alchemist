/*
 * Copyright (C) 2010-2016, Danilo Pianini and contributors
 * listed in the project's pom.xml file.
 * 
 * This file is part of Alchemist, and is distributed under the terms of
 * the GNU General Public License, with a linking exception, as described
 * in the file LICENSE in the Alchemist distribution's top directory.
 */

package it.unibo.alchemist.model.implementations.conditions;

import java.util.Collection;
import java.util.Map;

import it.unibo.alchemist.model.interfaces.Context;
import it.unibo.alchemist.model.interfaces.Environment;
import it.unibo.alchemist.model.interfaces.Node;

/**
 * Represents a condition on a neighbor. Formally this conditions is satisfied if at least one neighbor
 * satisfy the condition. 
 * @param <T> the concentration type.
 */
public abstract class AbstractNeighborCondition<T> extends AbstractCondition<T> {

    private static final long serialVersionUID = 1133243697147282024L;

    private final Environment<T> env;
    private final Node<T> node;

    /**
     * 
     * @param node the node hosting this condition
     * @param environment the current environment
     */
    protected AbstractNeighborCondition(final Node<T> node, final Environment<T> environment) {
        super(node);
        env = environment;
        this.node = node;
    }

    @Override
    public abstract AbstractNeighborCondition<T> cloneOnNewNode(final Node<T> n);

    @Override
    public Context getContext() {
        return Context.NEIGHBORHOOD;
    }

    /**
     * Searches in the whole neighborhood of the current node which neighbors satisfy the condition, and
     * returns a list of this neighbors.
     * @return a map of neighbors which satisfy the condition and their propensity
     */
    public Map<Node<T>, Double> getValidNeighbors() {
        return getValidNeighbors(env.getNeighborhood(node).getNeighbors());
    }

    /**
     * Searches in the given neighborhood which nodes satisfy the condition, and returns a list of valid 
     * neighbors. 
     * NOTE, it is NOT guaranteed that this method checks if the passed neighborhood is the actual neighborhood 
     * of the node. Make sure the passed neighborhood is up to date for avoid problems.
     * 
     * @param neighborhood the neighborhood of the node.
     * @return a map of neighbors which satisfy the condition and their propensity
     */
    public abstract Map<Node<T>, Double> getValidNeighbors(Collection<? extends Node<T>> neighborhood);

}
