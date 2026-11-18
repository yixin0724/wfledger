package org.workflowsim.data.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class PersistentHistoryMap<K, V> {
    private static final PersistentHistoryMap<?, ?> EMPTY =
            new PersistentHistoryMap<>(new Root<>(Collections.emptyList(), 0L, 0));

    private final Root<K, V> root;

    private PersistentHistoryMap(Root<K, V> root) {
        this.root = root;
    }

    @SuppressWarnings("unchecked")
    static <K, V> PersistentHistoryMap<K, V> empty() {
        return (PersistentHistoryMap<K, V>) EMPTY;
    }

    V get(K key) {
        Objects.requireNonNull(key, "key");
        for (Map<K, V> chunk : root.chunks) {
            if (chunk != null) {
                V value = chunk.get(key);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    boolean containsKey(K key) {

        return get(key) != null;
    }

    int size() {
        return root.distinctSize;
    }

    PersistentHistoryMap<K, V> with(K key, V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        long nextWriteCount = Math.addExact(root.writeCount, 1L);
        int nextSize = containsKey(key) ? root.distinctSize : Math.addExact(root.distinctSize, 1);

        List<Map<K, V>> chunks = new ArrayList<>(root.chunks);
        Map<K, V> carry = Collections.singletonMap(key, value);
        int level = 0;
        while (level < chunks.size() && chunks.get(level) != null) {

            Map<K, V> merged = new HashMap<>(chunks.get(level));
            merged.putAll(carry);
            carry = Collections.unmodifiableMap(merged);
            chunks.set(level, null);
            level++;
        }
        if (level == chunks.size()) {
            chunks.add(carry);
        } else {
            chunks.set(level, carry);
        }
        return new PersistentHistoryMap<>(new Root<>(chunks, nextWriteCount, nextSize));
    }

    private static final class Root<K, V> {
        private final List<Map<K, V>> chunks;
        private final long writeCount;
        private final int distinctSize;

        private Root(List<Map<K, V>> ownedChunks, long writeCount, int distinctSize) {

            this.chunks = Collections.unmodifiableList(ownedChunks);
            this.writeCount = writeCount;
            this.distinctSize = distinctSize;
        }
    }
}
