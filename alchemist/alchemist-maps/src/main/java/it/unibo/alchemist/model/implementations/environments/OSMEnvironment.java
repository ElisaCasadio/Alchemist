/*
 * Copyright (C) 2010-2015, Danilo Pianini and contributors
 * listed in the project's pom.xml file.
 * 
 * This file is part of Alchemist, and is distributed under the terms of
 * the GNU General Public License, with a linking exception, as described
 * in the file LICENSE in the Alchemist distribution's top directory.
 */
package it.unibo.alchemist.model.implementations.environments;

import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import it.unibo.alchemist.model.implementations.GraphHopperRoute;
import it.unibo.alchemist.model.implementations.positions.LatLongPosition;
import it.unibo.alchemist.model.interfaces.IGPSTrace;
import it.unibo.alchemist.model.interfaces.IMapEnvironment;
import it.unibo.alchemist.model.interfaces.Node;
import it.unibo.alchemist.model.interfaces.Position;
import it.unibo.alchemist.model.interfaces.IRoute;
import it.unibo.alchemist.model.interfaces.Time;
import it.unibo.alchemist.model.interfaces.Vehicle;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Triple;
import org.danilopianini.concurrency.FastReadWriteLock;
import org.danilopianini.io.FileUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.shapes.GHPoint;

/**
 * This class serves as template for more specific implementations of
 * environments using a map. It encloses the navigation logic, but leaves the
 * subclasses to decide how to provide map data (e.g. loading from disk or rely
 * on online services). The data is then stored in-memory for performance
 * reasons.
 * 
 * @param <T>
 */
public class OSMEnvironment<T> extends Continuous2DEnvironment<T> implements IMapEnvironment<T> {

    /**
     * Default maximum communication range (in meters).
     */
    public static final double DEFAULT_MAX_RANGE = 100;

    /**
     * Default value for id loading from traces.
     */
    public static final boolean DEFAULT_USE_TRACES_ID = false;

    /**
     * The default routing algorithm.
     */
    public static final String DEFAULT_ALGORITHM = "dijkstrabi";

    /**
     * The default routing strategy.
     */
    public static final String ROUTING_STRATEGY = "fastest";

    /**
     * The default value for the force nodes on streets option.
     */
    public static final boolean DEFAULT_ON_STREETS = true;

    /**
     * The default value for the discard of nodes too far from streets option.
     */
    public static final boolean DEFAULT_FORCE_STREETS = true;
    private static final int ENCODING_BASE = 36;
    private static final int ROUTES_CACHE_SIZE = 10000;
    private static final String MONITOR = "MapDisplay";
    private static final Logger L = LoggerFactory.getLogger(OSMEnvironment.class);
    private static final long serialVersionUID = -8100726226966471621L;
    /**
     * System file separator.
     */
    private static final String SLASH = System.getProperty("file.separator");
    /**
     * Alchemist's temp dir.
     */
    private static final String PERSISTENTPATH = System.getProperty("user.home") + SLASH + ".alchemist";

    private final String mapResource;
    private final TIntObjectMap<IGPSTrace> traces = new TIntObjectHashMap<>();
    private final boolean forceStreets, onlyStreet;
    private transient FastReadWriteLock mapLock;
    private transient Map<Vehicle, GraphHopper> navigators;
    private transient LoadingCache<Triple<Vehicle, Position, Position>, IRoute> routecache;

    /**
     * Builds a new {@link OSMEnvironment}, with nodes forced on streets,
     * discarding nodes too far off the map, and loading no GPS traces.
     * 
     * @param file
     *            the file path where the map data is stored
     * @throws IOException
     *             if the map file is not found, or it's not readable, or
     *             accessible, or a file system error occurred, or you kicked
     *             your hard drive while Alchemist was reading the map
     * @throws ClassNotFoundException
     *             if there is a gigantic bug in the distribution and
     *             {@link IGPSTrace} or {@link List} cannot be loaded
     */
    public OSMEnvironment(final String file) throws IOException, ClassNotFoundException {
        this(file, null, 0);
    }

    /**
     * @param file
     *            the file path where the map data is stored
     * @param onStreets
     *            if true, the nodes will be placed on the street nearest to the
     *            desired {@link Position}. This setting is automatically
     *            overridden if GPS traces are used, and a matching trace id is
     *            available for the node.
     * @param onlyOnStreets
     *            if true, the nodes which are too far from a street will be
     *            simply discarded. If false, they will be placed anyway, in the
     *            original position.
     * @throws IOException
     *             if the map file is not found, or it's not readable, or
     *             accessible, or a file system error occurred, or you kicked
     *             your hard drive while Alchemist was reading the map
     * @throws ClassNotFoundException
     *             if there is a gigantic bug in the distribution and
     *             {@link IGPSTrace} or {@link List} cannot be loaded
     */
    public OSMEnvironment(final String file, final boolean onStreets, final boolean onlyOnStreets) throws IOException, ClassNotFoundException {
        this(file, null, 0, onStreets, onlyOnStreets, DEFAULT_USE_TRACES_ID);
    }

    /**
     * @param file
     *            the file path where the map data is stored. Accepts OSM maps
     *            of any format (xml, osm, pbf). The map will be processed,
     *            optimized and stored for future use.
     * @param tfile
     *            the file path where the traces are stored. Supports only
     *            Alchemist's AGT traces. Can be null.
     * @param ttime
     *            the minimum time to consider when using the trace
     * @throws IOException
     *             if the map file is not found, or it's not readable, or
     *             accessible, or a file system error occurred, or you kicked
     *             your hard drive while Alchemist was reading the map
     * @throws ClassNotFoundException
     *             if there is a gigantic bug in the distribution and
     *             {@link IGPSTrace} or {@link List} cannot be loaded
     */
    public OSMEnvironment(final String file, final String tfile, final double ttime) throws IOException, ClassNotFoundException {
        this(file, tfile, ttime, DEFAULT_USE_TRACES_ID);
    }

    /**
     * @param file
     *            the file path where the map data is stored. Accepts OSM maps
     *            of any format (xml, osm, pbf). The map will be processed,
     *            optimized and stored for future use.
     * @param tfile
     *            the file path where the traces are stored. Supports only
     *            Alchemist's AGT traces. Can be null.
     * @param ttime
     *            the minimum time to consider when using the trace
     * @param useIds
     *            true if you want the association node - trace to be made with
     *            respect to the ids stored in the traces. Otherwise, ids are
     *            generated starting from 0.
     * @throws IOException
     *             if the map file is not found, or it's not readable, or
     *             accessible, or a file system error occurred, or you kicked
     *             your hard drive while Alchemist was reading the map
     * @throws ClassNotFoundException
     *             if there is a gigantic bug in the distribution and
     *             {@link IGPSTrace} or {@link List} cannot be loaded
     */
    public OSMEnvironment(final String file, final String tfile, final double ttime, final boolean useIds) throws IOException, ClassNotFoundException {
        this(file, tfile, ttime, DEFAULT_ON_STREETS, DEFAULT_FORCE_STREETS, useIds);
    }

    /**
     * @param file
     *            the file path where the map data is stored. Accepts OSM maps
     *            of any format (xml, osm, pbf). The map will be processed,
     *            optimized and stored for future use.
     * @param tfile
     *            the file path where the traces are stored. Supports only
     *            Alchemist's AGT traces. Can be null.
     * @param ttime
     *            the minimum time to consider when using the trace
     * @param onStreets
     *            if true, the nodes will be placed on the street nearest to the
     *            desired {@link Position}. This setting is automatically
     *            overridden if GPS traces are used, and a matching trace id is
     *            available for the node.
     * @param onlyOnStreets
     *            if true, the nodes which are too far from a street will be
     *            simply discarded. If false, they will be placed anyway, in the
     *            original position.
     * @param useIds
     *            true if you want the association node - trace to be made with
     *            respect to the ids stored in the traces. Otherwise, ids are
     *            generated starting from 0.
     * @throws IOException
     *             if the map file is not found, or it's not readable, or
     *             accessible, or a file system error occurred, or you kicked
     *             your hard drive while Alchemist was reading the map
     * @throws ClassNotFoundException
     *             if there is a gigantic bug in the distribution and
     *             {@link IGPSTrace} or {@link List} cannot be loaded
     */
    @SuppressWarnings("unchecked")
    protected OSMEnvironment(final String file, final String tfile, final double ttime, final boolean onStreets, final boolean onlyOnStreets, final boolean useIds) throws IOException, ClassNotFoundException {
        super();
        /*
         * Try to load as resource, then try to load a file
         */
        List<IGPSTrace> trcs = null;
        if (tfile != null) {
            trcs = (List<IGPSTrace>) FileUtilities.fileToObject(tfile);
            int idgen = 0;
            for (final IGPSTrace gps : trcs) {
                final IGPSTrace trace = gps.filter(ttime);
                if (trace.size() > 0) {
                    if (useIds) {
                        traces.put(trace.getId(), trace);
                    } else {
                        traces.put(idgen++, trace);
                    }
                }
            }
            if (!useIds) {
                L.info("Traces available for " + idgen + " nodes.");
            }
        }
        forceStreets = onStreets;
        onlyStreet = onlyOnStreets;
        mapResource = file;
        initAll(file);
    }

    private static boolean mkdirsIfNeeded(final File target) {
        return target.exists() || target.mkdirs();
    }

    private static boolean mkdirsIfNeeded(final String target) {
        return mkdirsIfNeeded(new File(target));
    }

    private void initAll(final String file) throws IOException {
        final URL resource = OSMEnvironment.class.getResource(file);
        final String resFile = resource == null ? "" : resource.getPath();
        final File mapFile = resFile.isEmpty() ? new File(file) : new File(resFile);
        if (!mapFile.exists()) {
            throw new FileNotFoundException(file);
        }
        final String dir = initDir(mapFile);
        final File workdir = new File(dir);
        mkdirsIfNeeded(workdir);
        navigators = new EnumMap<>(Vehicle.class);
        mapLock = new FastReadWriteLock();
        final boolean processOK = Arrays.stream(Vehicle.values())//.parallel()
            .map((v) -> {
                try {
                    final String internalWorkdir = workdir + SLASH + v;
                    final File iwdf = new File(internalWorkdir);
                    if (mkdirsIfNeeded(iwdf)) {
                        final GraphHopper gh = initNavigationSystem(mapFile, internalWorkdir, v);
                        mapLock.write();
                        navigators.put(v, gh);
                        mapLock.release();
                        return true;
                    }
                } catch (final Exception e) {
                    L.warn("", e);
                }
                L.warn("Unable to initialize navigation data for {}", v);
                return false;
            })
            .reduce((a, b) -> a && b).orElse(false);
        if (!processOK) {
            L.warn("Initialization completed with errors. Not all the navigation means supported by GraphHopper could be initialized with the map data provided.");
        }
    }

    private static synchronized GraphHopper initNavigationSystem(final File mapFile, final String internalWorkdir, final Vehicle v) throws IOException {
        final GraphHopper gh = new GraphHopper().forDesktop();
        gh.setOSMFile(mapFile.getAbsolutePath());
        gh.setGraphHopperLocation(internalWorkdir);
        gh.setEncodingManager(new EncodingManager(v.toString().toLowerCase()));
        try {
            gh.importOrLoad();
        } catch (final IllegalStateException e) {
            if (e.getMessage().matches("Version of \\w*? unsupported.*")) {
                L.warn("The map has already been processed with a different version of Alchemist. The previous files will be deleted.");
                FileUtils.deleteDirectory(new File(internalWorkdir));
                mkdirsIfNeeded(internalWorkdir);
                gh.importOrLoad();
            }
            throw e;
        }
        return gh;
    }

    private String initDir(final File mapfile) throws IOException {
        final String code = Long.toString(FileUtilities.fileCRC32sum(mapfile), ENCODING_BASE);
        final String append = SLASH + mapfile.getName() + code;
        final String[] prefixes = new String[] {
                PERSISTENTPATH,
                System.getProperty("java.io.tmpdir"),
                System.getProperty("user.dir"),
                "."};
        String dir = prefixes[0] + append;
        for (int i = 1; (!mkdirsIfNeeded(dir) || !canWriteOnDir(dir)) && i < prefixes.length; i++) {
            L.warn("Can not write on " + dir + ", trying " + prefixes[i]);
            dir = prefixes[i] + append;
        }
        if (!canWriteOnDir(dir)) {
            /*
             * Give up.
             */
            throw new IOException("None of: " + Arrays.toString(prefixes) + " is writeable. I can not initialize GraphHopper cache.");
        }
        return dir;
    }

    @Override
    public IRoute computeRoute(final Node<T> node, final Node<T> node2) {
        return computeRoute(node, getPosition(node2));
    }

    @Override
    public IRoute computeRoute(final Position p1, final Position p2) {
        return computeRoute(p1, p2, DEFAULT_VEHICLE);
    }

    @Override
    public IRoute computeRoute(final Position p1, final Position p2, final Vehicle vehicle) {
        if (routecache == null) {
            routecache = CacheBuilder
                .newBuilder()
                .maximumSize(ROUTES_CACHE_SIZE)
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .build(new CacheLoader<Triple<Vehicle, Position, Position>, IRoute>() {
                    @Override
                    public IRoute load(final Triple<Vehicle, Position, Position> key) {
                        final Vehicle vehicle = key.getLeft();
                        final Position p1 = key.getMiddle();
                        final Position p2 = key.getRight();
                        final GHRequest req = new GHRequest(p1.getCoordinate(1), p1.getCoordinate(0), p2.getCoordinate(1), p2.getCoordinate(0))
                                .setAlgorithm(DEFAULT_ALGORITHM)
                                .setVehicle(vehicle.toString())
                                .setWeighting(ROUTING_STRATEGY);
                        mapLock.read();
                        final GraphHopper gh = navigators.get(vehicle);
                        mapLock.release();
                        if (gh != null) {
                            final GHResponse resp = gh.route(req);
                            return new GraphHopperRoute(resp);
                        }
                        return null;
                    }
                });
        }
        try {
            return routecache.get(new ImmutableTriple<>(vehicle, p1, p2));
        } catch (ExecutionException e) {
            L.error("", e);
            throw new IllegalStateException("The navigator was unable to compute a route from " + p1 + " to " + p2 + " using the navigator " + vehicle + ". This is most likely a bug", e);
        }
    }

    @Override
    public IRoute computeRoute(final Node<T> node, final Position coord) {
        return computeRoute(node, coord, DEFAULT_VEHICLE);
    }

    @Override
    public IRoute computeRoute(final Node<T> node, final Position coord, final Vehicle vehicle) {
        return computeRoute(getPosition(node), coord, vehicle);
    }

    private Optional<Position> getNearestStreetPoint(final Position position) {
        assert position != null;
        mapLock.read();
        final GraphHopper gh = navigators.get(Vehicle.BIKE);
        mapLock.release();
        final QueryResult qr = gh.getLocationIndex().findClosest(position.getCoordinate(1), position.getCoordinate(0), EdgeFilter.ALL_EDGES);
        if (qr.isValid()) {
            final GHPoint pt = qr.getSnappedPoint();
            return Optional.of(new LatLongPosition(pt.lat, pt.lon));
        }
        return Optional.empty();
    }

    @Override
    public String getPreferredMonitor() {
        return MONITOR;
    }

    /**
     * @return the minimum latitude
     */
    protected double getMinLatitude() {
        return getOffset()[1];
    }

    /**
     * @return the maximum latitude
     */
    protected double getMaxLatitude() {
        return getOffset()[1] + getSize()[1];
    }

    /**
     * @return the minimum longitude
     */
    protected double getMinLongitude() {
        return getOffset()[0];
    }

    /**
     * @return the maximum longitude
     */
    protected double getMaxLongitude() {
        return getOffset()[0] + getSize()[0];
    }

    /**
     * There is a single case in which nodes are discarded: if there are no
     * traces for this node and nodes are required to lay on streets, but the
     * navigation engine can not resolve any such position.
     */
    @Override
    protected boolean nodeShouldBeAdded(final Node<T> node, final Position position) {
        assert node != null;
        return traces.containsKey(node.getId())
                || !onlyStreet
                || getNearestStreetPoint(position).isPresent();
    }

    @Override
    protected Position computeActualInsertionPosition(final Node<T> node, final Position position) {
        final IGPSTrace trace = traces.get(node.getId());
        if (trace == null) {
            /*
             * No traces available for this node. If it must be located on
             * streets, query the navigation engine for a street point.
             * Otherwise, put it where it is declared.
             */
            assert position != null;
            return forceStreets ? getNearestStreetPoint(position).orElse(position) : position;
        }
        assert trace.getPreviousPosition(0) != null;
        assert trace.getPreviousPosition(0).toPosition() != null;
        return trace.getPreviousPosition(0).toPosition();
    }

    @Override
    public Position getNextPosition(final Node<T> node, final Time time) {
        final IGPSTrace trace = traces.get(node.getId());
        if (trace == null) {
            return getPosition(node);
        }
        assert trace.getNextPosition(time.toDouble()) != null;
        return trace.getNextPosition(time.toDouble()).toPosition();
    }

    @Override
    public Position getPreviousPosition(final Node<T> node, final Time time) {
        final IGPSTrace trace = traces.get(node.getId());
        if (trace == null) {
            return getPosition(node);
        }
        assert trace.getPreviousPosition(time.toDouble()) != null;
        return trace.getPreviousPosition(time.toDouble()).toPosition();
    }

    @Override
    public Position getExpectedPosition(final Node<T> node, final Time time) {
        final IGPSTrace trace = traces.get(node.getId());
        if (trace == null) {
            return getPosition(node);
        }
        return trace.interpolate(time.toDouble()).toPosition();
    }

    @Override
    public IGPSTrace getTrace(final Node<T> node) {
        return traces.get(node.getId());
    }

    private boolean canWriteOnDir(final String dir) {
        return new File(dir).canWrite();
    }

    private void readObject(final ObjectInputStream s) throws IOException, ClassNotFoundException {
        s.defaultReadObject();
        initAll(mapResource);
    }

}
