package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.Job;

/**
 * R9 runtime-path probe: minimal deterministic fixture for the depth-sampling
 * branches of {@link OverheadParameters}.
 *
 * <p>Previously only the queue-delay channel of this class was covered, by
 * integration tests inside the test JVM (16.2% instruction coverage); the
 * post-processing/clustering/engine delay channels -- depth hit, depth 0 default,
 * absent entry, null maps, empty batch and null job -- were executed only
 * indirectly by a legacy example in a separate JVM, hence invisible to jacoco.
 * This test locks down the semantics of each branch one by one with a minimal Job
 * fixture inside the test JVM.</p>
 */
class OverheadParametersProbeTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    private static Job jobWithDepth(int depth) {
        Job job = new Job(1, 1000L);
        job.setDepth(depth);
        return job;
    }

    private static Map<Integer, DistributionGenerator> delayMap(int key) {
        Map<Integer, DistributionGenerator> map = new HashMap<Integer, DistributionGenerator>();
        map.put(key, new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL, 1.0, 100.0));
        return map;
    }

    @Test
    void depthKeyMatchesSamplePositiveDelaysForEveryChannel() {
        OverheadParameters op = new OverheadParameters(7, delayMap(2), delayMap(2), delayMap(2), delayMap(2), 42.0);
        Job job = jobWithDepth(2);

        assertTrue(op.getClustDelay(job) > 0.0, "a depth hit on the clustering delay must sample a positive value");
        assertTrue(op.getQueueDelay(job) > 0.0, "a depth hit on the queue delay must sample a positive value");
        assertTrue(op.getPostDelay(job) > 0.0, "a positive sample is required on a post-processing depth hit");

        List<Job> batch = new ArrayList<Job>();
        batch.add(job);
        assertTrue(op.getWEDDelay(batch) > 0.0, "a depth hit on the engine delay must sample a positive value");
    }

    @Test
    void depthZeroServesAsDefaultForUnmappedDepths() {
        OverheadParameters op = new OverheadParameters(0, delayMap(0), delayMap(0), delayMap(0), delayMap(0), 0.0);
        Job job = jobWithDepth(3);

        assertTrue(op.getClustDelay(job) > 0.0, "a missing depth entry must fall back to the depth 0 default entry");
        assertTrue(op.getQueueDelay(job) > 0.0, "a missing depth entry must fall back to the depth 0 default entry");
        assertTrue(op.getPostDelay(job) > 0.0, "a missing depth entry must fall back to the depth 0 default entry");

        List<Job> batch = new ArrayList<Job>();
        batch.add(job);
        assertTrue(op.getWEDDelay(batch) > 0.0, "a missing depth entry must fall back to the depth 0 default entry");
    }

    @Test
    void unmappedDepthWithoutDefaultYieldsZero() {
        OverheadParameters op = new OverheadParameters(0, delayMap(5), delayMap(5), delayMap(5), delayMap(5), 0.0);
        Job job = jobWithDepth(2);

        assertEquals(0.0, op.getClustDelay(job), 0.0);
        assertEquals(0.0, op.getQueueDelay(job), 0.0);
        assertEquals(0.0, op.getPostDelay(job), 0.0);

        List<Job> batch = new ArrayList<Job>();
        batch.add(job);
        assertEquals(0.0, op.getWEDDelay(batch), 0.0);
    }

    @Test
    void nullMapsYieldZeroWithoutSampling() {
        OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0.0);
        Job job = jobWithDepth(0);

        assertEquals(0.0, op.getClustDelay(job), 0.0);
        assertEquals(0.0, op.getQueueDelay(job), 0.0);
        assertEquals(0.0, op.getPostDelay(job), 0.0);
        List<Job> batch = new ArrayList<Job>();
        batch.add(job);
        assertEquals(0.0, op.getWEDDelay(batch), 0.0);
    }

    @Test
    void nullJobAndEmptyBatchLogUnsupportedAndYieldZero() {
        OverheadParameters op = new OverheadParameters(0, delayMap(0), delayMap(0), delayMap(0), delayMap(0), 0.0);

        // The historical semantics of a null job / empty batch is to print
        // "Not yet supported" and return 0; mute the console.
        Log.disable();
        assertEquals(0.0, op.getClustDelay(null), 0.0);
        assertEquals(0.0, op.getQueueDelay(null), 0.0);
        assertEquals(0.0, op.getPostDelay(null), 0.0);
        assertEquals(0.0, op.getWEDDelay(new ArrayList<Job>()), 0.0);
    }

    @Test
    void accessorsExposeConstructorValues() {
        Map<Integer, DistributionGenerator> wed = delayMap(1);
        Map<Integer, DistributionGenerator> queue = delayMap(2);
        Map<Integer, DistributionGenerator> post = delayMap(3);
        Map<Integer, DistributionGenerator> clust = delayMap(4);
        OverheadParameters op = new OverheadParameters(7, wed, queue, post, clust, 42.0);

        assertEquals(42.0, op.getBandwidth(), 0.0);
        assertEquals(7, op.getWEDInterval());
        assertSame(wed, op.getWEDDelay());
        assertSame(queue, op.getQueueDelay());
        assertSame(post, op.getPostDelay());
        assertSame(clust, op.getClustDelay());
    }
}
