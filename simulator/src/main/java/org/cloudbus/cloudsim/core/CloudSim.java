/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.core;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.predicates.Predicate;
import org.cloudbus.cloudsim.core.predicates.PredicateAny;
import org.cloudbus.cloudsim.core.predicates.PredicateNone;

public class CloudSim {

    private static final String CLOUDSIM_VERSION_STRING = "3.0";

    private static int cisId = -1;

    @SuppressWarnings("unused")
    private static int shutdownId = -1;

    private static CloudInformationService cis = null;

    private static final int NOT_FOUND = -1;

    @SuppressWarnings("unused")
    private static boolean traceFlag = false;

    private static Calendar calendar = null;

    private static double terminateAt = -1;

    private static final double DEFAULT_MIN_TIME_BETWEEN_EVENTS = 0.1;

    private static double minTimeBetweenEvents = DEFAULT_MIN_TIME_BETWEEN_EVENTS;

    private static void initCommonVariable(Calendar _calendar, boolean _traceFlag, int numUser) throws Exception {
        initialize();

        traceFlag = _traceFlag;

        if (_calendar == null) {
            calendar = Calendar.getInstance();
        } else {
            calendar = _calendar;
        }

        CloudSimShutdown shutdown = new CloudSimShutdown("CloudSimShutdown", numUser);
        shutdownId = shutdown.getId();
    }

    public static void init(int numUser, Calendar cal, boolean traceFlag) {
        try {
            initCommonVariable(cal, traceFlag, numUser);

            cis = new CloudInformationService("CloudInformationService");

            cisId = cis.getId();
        } catch (IllegalArgumentException s) {
            Log.printLine("CloudSim.init(): The simulation has been terminated due to an unexpected error");
            Log.printLine(s.getMessage());
        } catch (Exception e) {
            Log.printLine("CloudSim.init(): The simulation has been terminated due to an unexpected error");
            Log.printLine(e.getMessage());
        }
    }

    public static void init(int numUser, Calendar cal, boolean traceFlag, double periodBetweenEvents) {
        if (periodBetweenEvents <= 0) {
            throw new IllegalArgumentException(
                    "The minimal time between events should be positive, but is:" + periodBetweenEvents);
        }

        init(numUser, cal, traceFlag);
        minTimeBetweenEvents = periodBetweenEvents;
    }

    public static double startSimulation() throws NullPointerException {
        Log.printLine("Starting CloudSim version " + CLOUDSIM_VERSION_STRING);
        try {
            double clock = run();

            cisId = -1;
            shutdownId = -1;
            cis = null;
            calendar = null;
            traceFlag = false;

            return clock;
        } catch (IllegalArgumentException e) {

            throw e;
        }
    }

    public static void stopSimulation() throws NullPointerException {
        try {
            runStop();
        } catch (IllegalArgumentException e) {

            throw e;
        }
    }

    public static boolean terminateSimulation() {
        running = false;
        printMessage("Simulation: Reached termination time.");
        return true;
    }

    public static boolean terminateSimulation(double time) {
        if (time <= clock) {
            return false;
        } else {
            terminateAt = time;
        }
        return true;
    }

    public static double getMinTimeBetweenEvents() {
        return minTimeBetweenEvents;
    }

    public static Calendar getSimulationCalendar() {

        Calendar clone = calendar;
        if (calendar != null) {
            clone = (Calendar) calendar.clone();
        }

        return clone;
    }

    public static int getCloudInfoServiceEntityId() {
        return cisId;
    }

    public static List<Integer> getCloudResourceList() {
        if (cis == null) {
            return null;
        }

        return cis.getList();
    }

    private static List<SimEntity> entities;

    protected static FutureQueue future;

    protected static DeferredQueue deferred;

    private static double clock;

    private static boolean running;

    private static Map<String, SimEntity> entitiesByName;

    private static Map<Integer, Predicate> waitPredicates;

    private static boolean paused = false;

    private static long pauseAt = -1;

    private static boolean abruptTerminate = false;

    public static void reset() {
        running = false;
        paused = false;
        pauseAt = -1;
        abruptTerminate = false;
        terminateAt = -1;
        clock = 0.0;
        entities = null;
        entitiesByName = null;
        future = null;
        deferred = null;
        waitPredicates = null;
        cis = null;
        cisId = -1;
        shutdownId = -1;
        calendar = null;
        traceFlag = false;
        minTimeBetweenEvents = DEFAULT_MIN_TIME_BETWEEN_EVENTS;
    }

    protected static void initialize() {
        reset();
        Log.printLine("Initialising...");
        entities = new ArrayList<SimEntity>();
        entitiesByName = new LinkedHashMap<String, SimEntity>();
        future = new FutureQueue();
        deferred = new DeferredQueue();
        waitPredicates = new HashMap<Integer, Predicate>();
        clock = 0;
        running = false;
    }

    public static final PredicateAny SIM_ANY = new PredicateAny();

    public static final PredicateNone SIM_NONE = new PredicateNone();

    public static double clock() {
        return clock;
    }

    public static int getNumEntities() {
        return entities.size();
    }

    public static SimEntity getEntity(int id) {
        return entities.get(id);
    }

    public static SimEntity getEntity(String name) {
        return entitiesByName.get(name);
    }

    public static int getEntityId(String name) {
        SimEntity obj = entitiesByName.get(name);
        if (obj == null) {
            return NOT_FOUND;
        } else {
            return obj.getId();
        }
    }

    public static String getEntityName(int entityID) {
        try {
            return getEntity(entityID).getName();
        } catch (IllegalArgumentException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String getEntityName(Integer entityID) {
        if (entityID != null) {
            return getEntityName(entityID.intValue());
        }
        return null;
    }

    public static List<SimEntity> getEntityList() {

        List<SimEntity> list = new LinkedList<SimEntity>();
        list.addAll(entities);
        return list;
    }

    public static void addEntity(SimEntity e) {
        SimEvent evt;
        if (running) {

            evt = new SimEvent(SimEvent.CREATE, clock, 1, 0, 0, e);
            future.addEvent(evt);
        }
        if (e.getId() == -1) {
            int id = entities.size();
            e.setId(id);
            entities.add(e);
            entitiesByName.put(e.getName(), e);
        }
    }

    protected static void addEntityDynamically(SimEntity e) {
        if (e == null) {
            throw new IllegalArgumentException("Adding null entity.");
        } else {
            printMessage("Adding: " + e.getName());
        }
        e.startEntity();
    }

    public static boolean runClockTick() {
        SimEntity ent;
        boolean queue_empty;

        int entities_size = entities.size();

        for (int i = 0; i < entities_size; i++) {
            ent = entities.get(i);
            if (ent.getState() == SimEntity.RUNNABLE) {
                ent.run();
            }
        }

        if (future.size() > 0) {
            List<SimEvent> toRemove = new ArrayList<SimEvent>();
            Iterator<SimEvent> fit = future.iterator();
            queue_empty = false;
            SimEvent first = fit.next();
            processEvent(first);
            future.remove(first);

            fit = future.iterator();

            boolean trymore = fit.hasNext();
            while (trymore) {
                SimEvent next = fit.next();
                if (next.eventTime() == first.eventTime()) {
                    processEvent(next);
                    toRemove.add(next);
                    trymore = fit.hasNext();
                } else {
                    trymore = false;
                }
            }

            future.removeAll(toRemove);

        } else {
            queue_empty = true;
            running = false;
            printMessage("Simulation: No more future events");
        }

        return queue_empty;
    }

    public static void runStop() {
        printMessage("Simulation completed.");
    }

    public static void hold(int src, long delay) {
        SimEvent e = new SimEvent(SimEvent.HOLD_DONE, eventTimeAfter(delay), src);
        future.addEvent(e);
        entities.get(src).setState(SimEntity.HOLDING);
    }

    public static void pause(int src, double delay) {
        SimEvent e = new SimEvent(SimEvent.HOLD_DONE, eventTimeAfter(delay), src);
        future.addEvent(e);
        entities.get(src).setState(SimEntity.HOLDING);
    }

    public static void send(int src, int dest, double delay, int tag, Object data) {
        SimEvent e = new SimEvent(SimEvent.SEND, eventTimeAfter(delay), src, dest, tag, data);
        future.addEvent(e);
    }

    public static void sendFirst(int src, int dest, double delay, int tag, Object data) {
        SimEvent e = new SimEvent(SimEvent.SEND, eventTimeAfter(delay), src, dest, tag, data);
        future.addEventFirst(e);
    }

    private static double eventTimeAfter(double delay) {
        if (!Double.isFinite(delay) || delay < 0.0) {
            throw new IllegalArgumentException("Event delay must be finite and non-negative: " + delay);
        }
        double time = clock + delay;
        if (!Double.isFinite(time) || (delay > 0.0 && time <= clock)) {
            throw new IllegalArgumentException("Event delay " + delay + " cannot advance the finite simulation clock "
                    + clock + " at its current precision");
        }
        return time;
    }

    public static void wait(int src, Predicate p) {
        entities.get(src).setState(SimEntity.WAITING);
        if (p != SIM_ANY) {

            waitPredicates.put(src, p);
        }
    }

    public static int waiting(int d, Predicate p) {
        int count = 0;
        SimEvent event;
        Iterator<SimEvent> iterator = deferred.iterator();
        while (iterator.hasNext()) {
            event = iterator.next();
            if ((event.getDestination() == d) && (p.match(event))) {
                count++;
            }
        }
        return count;
    }

    public static SimEvent select(int src, Predicate p) {
        SimEvent ev = null;
        Iterator<SimEvent> iterator = deferred.iterator();
        while (iterator.hasNext()) {
            ev = iterator.next();
            if (ev.getDestination() == src && p.match(ev)) {
                iterator.remove();
                break;
            }
        }
        return ev;
    }

    public static SimEvent findFirstDeferred(int src, Predicate p) {
        SimEvent ev = null;
        Iterator<SimEvent> iterator = deferred.iterator();
        while (iterator.hasNext()) {
            ev = iterator.next();
            if (ev.getDestination() == src && p.match(ev)) {
                break;
            }
        }
        return ev;
    }

    public static SimEvent cancel(int src, Predicate p) {
        SimEvent ev = null;
        Iterator<SimEvent> iter = future.iterator();
        while (iter.hasNext()) {
            ev = iter.next();
            if (ev.getSource() == src && p.match(ev)) {
                iter.remove();
                break;
            }
        }

        return ev;
    }

    public static boolean cancelAll(int src, Predicate p) {
        SimEvent ev = null;
        int previousSize = future.size();
        Iterator<SimEvent> iter = future.iterator();
        while (iter.hasNext()) {
            ev = iter.next();
            if (ev.getSource() == src && p.match(ev)) {
                iter.remove();
            }
        }
        return previousSize < future.size();
    }

    private static void processEvent(SimEvent e) {
        int dest, src;
        SimEntity dest_ent;

        if (e.eventTime() < clock) {
            throw new IllegalArgumentException("Past event detected.");
        }
        clock = e.eventTime();

        switch (e.getType()) {
            case SimEvent.ENULL:
                throw new IllegalArgumentException("Event has a null type.");

            case SimEvent.CREATE:
                SimEntity newe = (SimEntity) e.getData();
                addEntityDynamically(newe);
                break;

            case SimEvent.SEND:
                dest = e.getDestination();
                if (dest < 0) {
                    throw new IllegalArgumentException("Attempt to send to a null entity detected.");
                } else {
                    int tag = e.getTag();
                    dest_ent = entities.get(dest);
                    if (dest_ent.getState() == SimEntity.WAITING) {
                        Integer destObj = Integer.valueOf(dest);
                        Predicate p = waitPredicates.get(destObj);
                        if ((p == null) || (tag == 9999) || (p.match(e))) {
                            dest_ent.setEventBuffer((SimEvent) e.clone());
                            dest_ent.setState(SimEntity.RUNNABLE);
                            waitPredicates.remove(destObj);
                        } else {
                            deferred.addEvent(e);
                        }
                    } else {
                        deferred.addEvent(e);
                    }
                }
                break;

            case SimEvent.HOLD_DONE:
                src = e.getSource();
                if (src < 0) {
                    throw new IllegalArgumentException("Null entity holding.");
                } else {
                    entities.get(src).setState(SimEntity.RUNNABLE);
                }
                break;

            default:
                break;
        }
    }

    public static void runStart() {
        running = true;

        for (SimEntity ent : entities) {
            ent.startEntity();
        }

        printMessage("Entities started.");
    }

    public static boolean running() {
        return running;
    }

    public static boolean pauseSimulation() {
        paused = true;
        return paused;
    }

    public static boolean pauseSimulation(long time) {
        if (time <= clock) {
            return false;
        } else {
            pauseAt = time;
        }
        return true;
    }

    public static boolean resumeSimulation() {
        paused = false;

        if (pauseAt <= clock) {
            pauseAt = -1;
        }

        return !paused;
    }

    public static double run() {
        if (!running) {
            runStart();
        }
        while (true) {
            if (runClockTick() || abruptTerminate) {
                break;
            }

            if (terminateAt > 0.0 && clock >= terminateAt) {
                terminateSimulation();
                clock = terminateAt;
                break;
            }

            if (pauseAt != -1
                    && ((future.size() > 0
                                    && clock <= pauseAt
                                    && pauseAt <= future.iterator().next().eventTime())
                            || future.size() == 0 && pauseAt <= clock)) {
                pauseSimulation();
                clock = pauseAt;
            }

            while (paused) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        double clock = clock();

        finishSimulation();
        runStop();

        return clock;
    }

    public static void finishSimulation() {

        if (!abruptTerminate) {
            for (SimEntity ent : entities) {
                if (ent.getState() != SimEntity.FINISHED) {
                    ent.run();
                }
            }
        }

        for (SimEntity ent : entities) {
            ent.shutdownEntity();
        }

        entities = null;
        entitiesByName = null;
        future = null;
        deferred = null;
        clock = 0L;
        running = false;

        waitPredicates = null;
        paused = false;
        pauseAt = -1;
        abruptTerminate = false;
    }

    public static void abruptallyTerminate() {
        abruptTerminate = true;
    }

    private static void printMessage(String message) {
        Log.printLine(message);
    }

    public static boolean isPaused() {
        return paused;
    }
}
