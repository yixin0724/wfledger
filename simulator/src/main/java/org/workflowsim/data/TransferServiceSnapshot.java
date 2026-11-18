package org.workflowsim.data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class TransferServiceSnapshot {

    public static final class Resource {
        private final String key;
        private final double capacityBytesPerSecond;
        private final BigDecimal assignedRateBytesPerSecond;
        private final int activeFlowCount;
        private final long pathMultiplicityCount;

        private Resource(
                String key,
                double capacityBytesPerSecond,
                BigDecimal assignedRateBytesPerSecond,
                int activeFlowCount,
                long pathMultiplicityCount) {
            this.key = key;
            this.capacityBytesPerSecond = capacityBytesPerSecond;
            this.assignedRateBytesPerSecond = assignedRateBytesPerSecond;
            this.activeFlowCount = activeFlowCount;
            this.pathMultiplicityCount = pathMultiplicityCount;
        }

        public String getKey() {
            return key;
        }

        public double getCapacityBytesPerSecond() {
            return capacityBytesPerSecond;
        }

        public BigDecimal getAssignedRateBytesPerSecond() {
            return assignedRateBytesPerSecond;
        }

        public int getActiveFlowCount() {
            return activeFlowCount;
        }

        public long getPathMultiplicityCount() {
            return pathMultiplicityCount;
        }
    }

    public static final class Flow {
        private final long transferId;
        private final double bytes;
        private final double remainingBytes;
        private final double rateBytesPerSecond;
        private final double nominalRateBytesPerSecond;
        private final double admittedAt;
        private final List<String> resources;

        Flow(
                long transferId,
                double bytes,
                double remainingBytes,
                double rateBytesPerSecond,
                double nominalRateBytesPerSecond,
                double admittedAt,
                List<String> resources) {
            this.transferId = transferId;
            this.bytes = bytes;
            this.remainingBytes = remainingBytes;
            this.rateBytesPerSecond = rateBytesPerSecond;
            this.nominalRateBytesPerSecond = nominalRateBytesPerSecond;
            this.admittedAt = admittedAt;
            this.resources = Collections.unmodifiableList(new ArrayList<String>(resources));
        }

        public long getTransferId() {
            return transferId;
        }

        public double getBytes() {
            return bytes;
        }

        public double getRemainingBytes() {
            return remainingBytes;
        }

        public double getRateBytesPerSecond() {
            return rateBytesPerSecond;
        }

        public double getNominalRateBytesPerSecond() {
            return nominalRateBytesPerSecond;
        }

        public double getAdmittedAt() {
            return admittedAt;
        }

        public List<String> getResources() {
            return resources;
        }
    }

    private static final class ResourceTotals {
        private final double capacity;
        private BigDecimal assignedRate = BigDecimal.ZERO;
        private int activeFlows;
        private long pathMultiplicity;

        private ResourceTotals(double capacity) {
            this.capacity = capacity;
        }
    }

    private final double observedThrough;
    private final boolean shared;
    private final Map<String, Resource> resources;
    private final Map<Long, Flow> transfers;

    TransferServiceSnapshot(
            double observedThrough, boolean shared, Map<String, Double> capacities, Map<Long, Flow> transfers) {
        this.observedThrough = observedThrough;
        this.shared = shared;
        this.transfers = Collections.unmodifiableMap(new LinkedHashMap<Long, Flow>(transfers));

        Map<String, ResourceTotals> totals = new TreeMap<String, ResourceTotals>();
        for (Map.Entry<String, Double> entry : capacities.entrySet()) {
            totals.put(entry.getKey(), new ResourceTotals(entry.getValue()));
        }
        for (Flow flow : this.transfers.values()) {
            Map<String, Integer> multiplicities = new LinkedHashMap<String, Integer>();
            for (String key : flow.resources) {
                Integer previous = multiplicities.get(key);
                multiplicities.put(key, previous == null ? 1 : previous + 1);
            }
            BigDecimal exactRate = new BigDecimal(flow.rateBytesPerSecond);
            for (Map.Entry<String, Integer> entry : multiplicities.entrySet()) {
                ResourceTotals total = totals.get(entry.getKey());
                int multiplicity = entry.getValue();
                total.assignedRate = total.assignedRate.add(exactRate.multiply(BigDecimal.valueOf(multiplicity)));
                total.activeFlows++;
                total.pathMultiplicity += multiplicity;
            }
        }
        Map<String, Resource> copiedResources = new LinkedHashMap<String, Resource>();
        for (Map.Entry<String, ResourceTotals> entry : totals.entrySet()) {
            ResourceTotals total = entry.getValue();
            copiedResources.put(
                    entry.getKey(),
                    new Resource(
                            entry.getKey(),
                            total.capacity,
                            total.assignedRate,
                            total.activeFlows,
                            total.pathMultiplicity));
        }
        this.resources = Collections.unmodifiableMap(copiedResources);
    }

    public double getObservedThrough() {
        return observedThrough;
    }

    public boolean isShared() {
        return shared;
    }

    public Map<String, Resource> getResources() {
        return resources;
    }

    public Map<Long, Flow> getTransfers() {
        return transfers;
    }
}
