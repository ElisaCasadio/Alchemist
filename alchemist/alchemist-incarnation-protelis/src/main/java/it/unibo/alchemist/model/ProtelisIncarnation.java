/*
 * Copyright (C) 2010-2015, Danilo Pianini and contributors
 * listed in the project's pom.xml file.
 * 
 * This file is part of Alchemist, and is distributed under the terms of
 * the GNU General Public License, with a linking exception, as described
 * in the file LICENSE in the Alchemist distribution's top directory.
 */
package it.unibo.alchemist.model;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.math3.random.RandomGenerator;
import org.danilopianini.lang.LangUtils;
import org.protelis.lang.ProtelisLoader;
import org.protelis.lang.datatype.DeviceUID;
import org.protelis.vm.ExecutionContext;
import org.protelis.vm.ExecutionEnvironment;
import org.protelis.vm.NetworkManager;
import org.protelis.vm.ProtelisProgram;
import org.protelis.vm.ProtelisVM;
import org.protelis.vm.impl.AbstractExecutionContext;
import org.protelis.vm.impl.SimpleExecutionEnvironment;
import org.protelis.vm.util.CodePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;

import it.unibo.alchemist.model.implementations.actions.RunProtelisProgram;
import it.unibo.alchemist.model.implementations.actions.SendToNeighbor;
import it.unibo.alchemist.model.implementations.conditions.ComputationalRoundComplete;
import it.unibo.alchemist.model.implementations.molecules.SimpleMolecule;
import it.unibo.alchemist.model.implementations.nodes.ProtelisNode;
import it.unibo.alchemist.model.implementations.reactions.ChemicalReaction;
import it.unibo.alchemist.model.implementations.reactions.Event;
import it.unibo.alchemist.model.implementations.timedistributions.DiracComb;
import it.unibo.alchemist.model.implementations.timedistributions.ExponentialTime;
import it.unibo.alchemist.model.implementations.times.DoubleTime;
import it.unibo.alchemist.model.interfaces.Action;
import it.unibo.alchemist.model.interfaces.Condition;
import it.unibo.alchemist.model.interfaces.Environment;
import it.unibo.alchemist.model.interfaces.Incarnation;
import it.unibo.alchemist.model.interfaces.Molecule;
import it.unibo.alchemist.model.interfaces.Node;
import it.unibo.alchemist.model.interfaces.Reaction;
import it.unibo.alchemist.model.interfaces.TimeDistribution;

/**
 */
public final class ProtelisIncarnation implements Incarnation<Object> {

    /**
     * The name that can be used in a property to refer to the extracted value.
     */
    public static final String VALUE_TOKEN = "<value>";
    private static final Logger L = LoggerFactory.getLogger(ProtelisIncarnation.class);
    private final LoadingCache<CacheKey, SynchronizedVM> cache = CacheBuilder
            .newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(new CacheLoader<CacheKey, SynchronizedVM>() {
                @Override
                public SynchronizedVM load(final CacheKey key) {
                    return new SynchronizedVM(key);
                }
            });

    private static final class CacheKey {
        private final WeakReference<Node<Object>> node;
        private final Molecule molecule;
        private final String property;
        private CacheKey(final Node<Object> node, final Molecule mol, final String prop) {
            this.node = new WeakReference<>(node);
            molecule = mol;
            property = prop;
        }
        @Override
        public int hashCode() {
            final Node<Object> n = node.get();
            return molecule.hashCode() ^ property.hashCode() ^ (n == null ? 0 : n.hashCode());
        }
        @Override
        public boolean equals(final Object obj) {
            return obj instanceof CacheKey
                    && ((CacheKey) obj).node.get() == node.get()
                    && ((CacheKey) obj).molecule.equals(molecule)
                    && ((CacheKey) obj).property.equals(property);
        }
    }

    private static final class SynchronizedVM {
        private final Optional<ProtelisVM> vm;
        private final Semaphore mutex = new Semaphore(1);
        private final CacheKey key;
        private SynchronizedVM(final CacheKey key) {
            this.key = key;
            ProtelisVM myVM = null;
            if (key.property != null && !key.property.trim().isEmpty()) {
                try {
                    final String baseProgram = "env.get(\"" + ((SimpleMolecule) key.molecule).toFasterString() + "\")";
                    myVM = new ProtelisVM(
                            ProtelisLoader.parse(key.property.replace(VALUE_TOKEN, baseProgram)),
                            new DummyContext(key.node.get()));
                } catch (RuntimeException ex) {
                    L.warn("Ignored program \n" + key.property + "\n, since it is not a valid Protelis program.", ex);
                }
            }
            vm = Optional.ofNullable(myVM);
        }
        public Object runCycle() {
            final Node<Object> node = key.node.get();
            if (node == null) {
                throw new IllegalStateException("The node should never get null");
            }
            if (vm.isPresent()) {
                final ProtelisVM myVM = vm.get();
                mutex.acquireUninterruptibly();
                myVM.runCycle();
                mutex.release();
                return myVM.getCurrentValue();
            }
            return node.getConcentration(key.molecule);
        }
    }

    @Override
    public double getProperty(final Node<Object> node, final Molecule mol, final String prop) {
        try {
            final SynchronizedVM vm = cache.get(new CacheKey(node, mol, prop));
            final Object val = vm.runCycle();
            if (val instanceof Number) {
                return ((Number) val).doubleValue();
            } else if (val instanceof String) {
                try {
                    return Double.parseDouble(val.toString());
                } catch (final NumberFormatException e) {
                    if (val.equals(prop)) {
                        return 1;
                    }
                    return 0;
                }
            } else if (val instanceof Boolean) {
                final Boolean cond = (Boolean) val;
                if (cond) {
                    return 1d;
                } else {
                    return 0d;
                }
            }
        } catch (ExecutionException e) {
            L.error("Bug in " + getClass().getName() + ": getProperty should never fail.", e);
        }
        return Double.NaN;
    }

    @Override
    public Molecule createMolecule(final String s) {
        return new SimpleMolecule(s);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

    /**
     * An {@link ExecutionEnvironment} that can read and shadow the content of a
     * {@link Node}, but cannot modify it. This is used to prevent badly written
     * properties to interact with the simulation flow.
     */
    public static class ProtectedExecutionEnvironment implements ExecutionEnvironment {
        private final ExecutionEnvironment shadow = new SimpleExecutionEnvironment();
        private final Node<?> node;

        /**
         * @param node the {@link Node}
         */
        public ProtectedExecutionEnvironment(final Node<?> node) {
            this.node = node;
        }

        @Override
        public void setup() {
        }
        @Override
        public Object remove(final String id) {
            return shadow.remove(id);
        }
        @Override
        public boolean put(final String id, final Object v) {
            return shadow.put(id, v);
        }
        @Override
        public boolean has(final String id) {
            return shadow.has(id) || node.contains(new SimpleMolecule(id));
        }
        @Override
        public Object get(final String id, final Object defaultValue) {
            return Optional.<Object>ofNullable(get(id)).orElse(defaultValue);
        }
        @Override
        public Object get(final String id) {
            return shadow.get(id, node.getConcentration(new SimpleMolecule(id)));
        }
        @Override
        public void commit() {
        }
    }

    /**
     * An {@link ExecutionContext} that operates over a node, but does not
     * modify it.
     */
    public static class DummyContext extends AbstractExecutionContext {
        private final Node<?> node;
        DummyContext(final Node<?> node) {
            super(new ProtectedExecutionEnvironment(node), new NetworkManager() {
                @Override
                public void shareState(final Map<CodePath, Object> toSend) {
                }
                @Override
                public Map<DeviceUID, Map<CodePath, Object>> getNeighborState() {
                    return Collections.emptyMap();
                }
            });
            this.node = node;
        }

        @Override
        public DeviceUID getDeviceUID() {
            if (node instanceof ProtelisNode) {
                return (ProtelisNode) node;
            }
            throw new IllegalStateException("You tried to compute a Protelis device UID, on a non-Protelis node");
        }
        @Override
        public Number getCurrentTime() {
            return 0;
        }
        @Override
        public double nextRandomDouble() {
            return Math.random();
        }
        @Override
        protected AbstractExecutionContext instance() {
            return this;
        }

    }

    @Override
    public Node<Object> createNode(final RandomGenerator rand, final Environment<Object> env, final String param) {
        return new ProtelisNode(env);
    }

    @Override
    public TimeDistribution<Object> createTimeDistribution(
            final RandomGenerator rand,
            final Environment<Object> env,
            final Node<Object> node,
            final String param) {
        if (param == null) {
            return new ExponentialTime<>(Double.POSITIVE_INFINITY, rand);
        }
        double frequency;
        try {
            frequency = Double.parseDouble(param);
        } catch (final NumberFormatException e) {
            frequency = 1;
        }
        return new DiracComb<>(new DoubleTime(rand.nextDouble() / frequency), frequency);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Reaction<Object> createReaction(final RandomGenerator rand, final Environment<Object> env,
            final Node<Object> node, final TimeDistribution<Object> time, final String param) {
        LangUtils.requireNonNull(node, time);
        final boolean isSend = "send".equalsIgnoreCase(param);
        final Reaction<Object> result = isSend ? new ChemicalReaction<>(node, time) : new Event<>(node, time);
        if (param != null) {
            result.setActions(Lists.newArrayList(createAction(rand, env, node, time, result, param)));
        }
        if (isSend) {
            result.setConditions(Lists.newArrayList(createCondition(rand, env, node, time, result, null)));
        }
        return result;
    }

    @Override
    public Condition<Object> createCondition(final RandomGenerator rand, final Environment<Object> env,
            final Node<Object> node, final TimeDistribution<Object> time, final Reaction<Object> reaction,
            final String param) {
        if (node instanceof ProtelisNode) {
            final ProtelisNode pNode = (ProtelisNode) node;
            /*
             * The list of ProtelisPrograms that have already been completed with a ComputationalRoundComplete condition
             */
            final List<RunProtelisProgram> alreadyDone = pNode.getReactions()
                .parallelStream()
                .flatMap(r -> r.getConditions().parallelStream())
                .filter(c -> c instanceof ComputationalRoundComplete)
                .map(c -> (ComputationalRoundComplete) c)
                .flatMap(crc -> crc.getInfluencingMolecules().parallelStream())
                .filter(mol -> mol instanceof RunProtelisProgram)
                .map(mol -> (RunProtelisProgram) mol)
                .collect(Collectors.toList());
            final List<RunProtelisProgram> pList = getIncomplete(pNode, alreadyDone);
            if (pList.isEmpty()) {
                throw new IllegalStateException("There is no program requiring a "
                        + ComputationalRoundComplete.class.getSimpleName() + " condition");
            }
            if (pList.size() > 1) {
                throw new IllegalStateException("There are too many programs requiring a "
                        + ComputationalRoundComplete.class.getName() + " condition: " + pList);
            }
            return new ComputationalRoundComplete(pNode, pList.get(0));
        }
        throw new IllegalArgumentException("The node must be an instance of " + ProtelisNode.class.getSimpleName()
                + ", it is a " + node.getClass().getName() + " instead");
    }

    @Override
    public Action<Object> createAction(final RandomGenerator rand, final Environment<Object> env,
            final Node<Object> node, final TimeDistribution<Object> time, final Reaction<Object> reaction,
            final String param) {
        Objects.requireNonNull(param);
        if (node instanceof ProtelisNode) {
            final ProtelisNode pNode = (ProtelisNode) node;
            if (param.equalsIgnoreCase("send")) {
                final List<RunProtelisProgram> alreadyDone = pNode.getReactions()
                    .parallelStream()
                    .flatMap(r -> r.getActions().parallelStream())
                    .filter(a -> a instanceof SendToNeighbor)
                    .map(c -> (SendToNeighbor) c)
                    .map(crc -> crc.getProtelisProgram())
                    .collect(Collectors.toList());
                final List<RunProtelisProgram> pList = getIncomplete(pNode, alreadyDone);
                if (pList.isEmpty()) {
                    throw new IllegalStateException("There is no program requiring a "
                            + SendToNeighbor.class.getSimpleName() + " action");
                }
                if (pList.size() > 1) {
                    throw new IllegalStateException("There are too many programs requiring a "
                            + SendToNeighbor.class.getName() + " action: " + pList);
                }
                return new SendToNeighbor(pNode, pList.get(0));
            } else {
                try {
                    return new RunProtelisProgram(env, pNode, reaction, rand, param);
                } catch (ClassNotFoundException | RuntimeException e) {
                    throw new IllegalArgumentException("Could not create the requested Protelis program: " + param, e);
                }
            }
        }
        throw new IllegalArgumentException("The node must be an instance of " + ProtelisNode.class.getSimpleName()
                + ", it is a " + node.getClass().getName() + " instead");
    }

    private static List<RunProtelisProgram> getIncomplete(final ProtelisNode pNode, final List<RunProtelisProgram> alreadyDone) {
        return pNode.getReactions().parallelStream()
                /*
                 * Get the actions
                 */
                .flatMap(r -> r.getActions().parallelStream())
                /*
                 * Get only the ProtelisPrograms
                 */
                .filter(a -> a instanceof RunProtelisProgram)
                .map(a -> (RunProtelisProgram) a)
                /*
                 * Retain only those ProtelisPrograms that have no associated ComputationalRoundComplete.
                 * 
                 * Only one should be available.
                 */
                .filter(prog -> !alreadyDone.contains(prog))
                .collect(Collectors.toList());
    }

    @Override
    public Object createConcentration(final String s) {
        try {
            final ProtelisProgram program = ProtelisLoader
                    .parse(Objects.requireNonNull(s, "The concentration can not be null."));
            final ProtelisVM vm = new ProtelisVM(program, new DummyContext(null));
            vm.runCycle();
            return vm.getCurrentValue();
        } catch (IllegalArgumentException e) {
            /*
             * Not a valid program: inject the String itself
             */
            return s;
        }
    }

}
