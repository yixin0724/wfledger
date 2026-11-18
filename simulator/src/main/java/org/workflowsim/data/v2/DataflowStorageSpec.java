package org.workflowsim.data.v2;

public final class DataflowStorageSpec {
    private static final double BYTES_PER_DECIMAL_MEGABYTE = 1_000_000.0;

    private final int attachmentHostId;
    private final double readBandwidthMbPerSecond;
    private final double writeBandwidthMbPerSecond;
    private final double networkBandwidthMbPerSecond;

    private DataflowStorageSpec(
            int attachmentHostId,
            double readBandwidthMbPerSecond,
            double writeBandwidthMbPerSecond,
            double networkBandwidthMbPerSecond) {
        this.attachmentHostId = attachmentHostId;
        this.readBandwidthMbPerSecond = readBandwidthMbPerSecond;
        this.writeBandwidthMbPerSecond = writeBandwidthMbPerSecond;
        this.networkBandwidthMbPerSecond = networkBandwidthMbPerSecond;
    }

    public static DataflowStorageSpec of(
            int attachmentHostId,
            double readBandwidthMbPerSecond,
            double writeBandwidthMbPerSecond,
            double networkBandwidthMbPerSecond) {
        if (attachmentHostId < 0) {
            throw new IllegalArgumentException("attachmentHostId must be nonnegative");
        }
        validateBandwidth("readBandwidthMbPerSecond", readBandwidthMbPerSecond);
        validateBandwidth("writeBandwidthMbPerSecond", writeBandwidthMbPerSecond);
        validateBandwidth("networkBandwidthMbPerSecond", networkBandwidthMbPerSecond);
        return new DataflowStorageSpec(
                attachmentHostId, readBandwidthMbPerSecond, writeBandwidthMbPerSecond, networkBandwidthMbPerSecond);
    }

    private static void validateBandwidth(String name, double bandwidthMbPerSecond) {
        if (!Double.isFinite(bandwidthMbPerSecond) || bandwidthMbPerSecond <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and greater than zero (decimal MB/s)");
        }
        double capacityBytesPerSecond = bandwidthMbPerSecond * BYTES_PER_DECIMAL_MEGABYTE;
        if (!Double.isFinite(capacityBytesPerSecond) || capacityBytesPerSecond < Double.MIN_NORMAL) {
            throw new IllegalArgumentException(name + " must convert to a finite normal bytes-per-second capacity");
        }
    }

    public int getAttachmentHostId() {
        return attachmentHostId;
    }

    public double getReadBandwidthMbPerSecond() {
        return readBandwidthMbPerSecond;
    }

    public double getWriteBandwidthMbPerSecond() {
        return writeBandwidthMbPerSecond;
    }

    public double getNetworkBandwidthMbPerSecond() {
        return networkBandwidthMbPerSecond;
    }

    public double getReadCapacityBytesPerSecond() {
        return readBandwidthMbPerSecond * BYTES_PER_DECIMAL_MEGABYTE;
    }

    public double getWriteCapacityBytesPerSecond() {
        return writeBandwidthMbPerSecond * BYTES_PER_DECIMAL_MEGABYTE;
    }

    public double getNetworkCapacityBytesPerSecond() {
        return networkBandwidthMbPerSecond * BYTES_PER_DECIMAL_MEGABYTE;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DataflowStorageSpec)) {
            return false;
        }
        DataflowStorageSpec that = (DataflowStorageSpec) other;
        return attachmentHostId == that.attachmentHostId
                && Double.compare(readBandwidthMbPerSecond, that.readBandwidthMbPerSecond) == 0
                && Double.compare(writeBandwidthMbPerSecond, that.writeBandwidthMbPerSecond) == 0
                && Double.compare(networkBandwidthMbPerSecond, that.networkBandwidthMbPerSecond) == 0;
    }

    @Override
    public int hashCode() {
        int result = attachmentHostId;
        result = 31 * result + Double.hashCode(readBandwidthMbPerSecond);
        result = 31 * result + Double.hashCode(writeBandwidthMbPerSecond);
        result = 31 * result + Double.hashCode(networkBandwidthMbPerSecond);
        return result;
    }
}
