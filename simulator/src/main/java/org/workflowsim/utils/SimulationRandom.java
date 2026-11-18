/*
 * Copyright 2026 WorkflowSim maintainers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.workflowsim.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.Well19937c;

public final class SimulationRandom {

    private static final Map<String, Random> SEED_STREAMS = new HashMap<>();
    private static long rootSeed = 0L;

    private SimulationRandom() {}

    public static synchronized void reset(long seed) {
        rootSeed = seed;
        SEED_STREAMS.clear();
        DistributionGenerator.resetStreamAllocation();
    }

    public static synchronized Random newJavaRandom(String component) {
        return new Random(nextSeed(component));
    }

    public static synchronized RandomGenerator newApacheRandom(String component) {
        return new Well19937c(nextSeed(component));
    }

    private static long nextSeed(String component) {
        if (component == null || component.isEmpty()) {
            throw new IllegalArgumentException("Random component name must not be empty");
        }
        Random stream = SEED_STREAMS.get(component);
        if (stream == null) {
            stream = new Random(mix64(rootSeed ^ stableHash(component)));
            SEED_STREAMS.put(component, stream);
        }
        return stream.nextLong();
    }

    private static long stableHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
