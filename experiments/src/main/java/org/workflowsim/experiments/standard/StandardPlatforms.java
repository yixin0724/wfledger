package org.workflowsim.experiments.standard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.workflowsim.platform.PlatformProfile;

/** Single source of truth for the standard-study platform constants. */
public final class StandardPlatforms {

    public static final double MIN_MIPS = 500.0;

    public static final double MAX_MIPS = 2000.0;

    public static final int[] STANDARD_VM_COUNTS = {8, 16, 32};

    public static final double PRICE_PER_MIPS_SECOND = 1.0e-3;

    public static final double WATTS_PER_MIPS = 0.1;

    public static final double DEFAULT_IDLE_TO_MAX_POWER_RATIO = 0.6;

    public static final double[] OPTERON_2218_VOLTS = {1.30, 1.25, 1.20, 1.15, 1.10, 1.05};

    public static final double[] OPTERON_2218_GHZ = {2.6, 2.4, 2.2, 2.0, 1.8, 1.0};

    static final int HOST_RAM_MB = 4096;
    static final long HOST_BANDWIDTH = 10_000L;
    static final long HOST_STORAGE_MB = 1_000_000L;
    static final int VM_RAM_MB = 512;
    static final long VM_BANDWIDTH = 1_000L;
    static final long VM_IMAGE_MB = 1_000L;
    static final long STORAGE_CAPACITY_MB = 1_000_000_000_000L;

    private StandardPlatforms() {}

    public static double mips(int vmCount, int index) {
        if (vmCount < 2 || index < 0 || index >= vmCount) {
            throw new IllegalArgumentException(
                    "Standard fleet requires at least 2 VMs and an index in range: N=" + vmCount + " i=" + index);
        }
        return MIN_MIPS + (MAX_MIPS - MIN_MIPS) * index / (vmCount - 1);
    }

    public static double meanMips(int vmCount) {
        double sum = 0.0;
        for (int i = 0; i < vmCount; i++) {
            sum += mips(vmCount, i);
        }
        return sum / vmCount;
    }

    public static PlatformProfile plain(int vmCount, double storageRateMbPerSecond, double idleToMaxPowerRatio) {
        return build(vmCount, storageRateMbPerSecond, idleToMaxPowerRatio, false);
    }

    public static PlatformProfile tiered(int vmCount, double storageRateMbPerSecond, double idleToMaxPowerRatio) {
        return build(vmCount, storageRateMbPerSecond, idleToMaxPowerRatio, true);
    }

    public static List<PlatformProfile.FrequencyTier> opteron2218Tiers(double nominalMips, double idleToMaxPowerRatio) {
        requireRatio(idleToMaxPowerRatio);
        double max = WATTS_PER_MIPS * nominalMips;
        double idle = idleToMaxPowerRatio * max;
        double v0 = OPTERON_2218_VOLTS[0];
        double f0 = OPTERON_2218_GHZ[0];
        List<PlatformProfile.FrequencyTier> tiers = new ArrayList<PlatformProfile.FrequencyTier>();
        for (int j = 0; j < OPTERON_2218_GHZ.length; j++) {
            double speed = j == 0 ? nominalMips : nominalMips * OPTERON_2218_GHZ[j] / f0;
            double relativeDynamic =
                    (OPTERON_2218_VOLTS[j] / v0) * (OPTERON_2218_VOLTS[j] / v0) * (OPTERON_2218_GHZ[j] / f0);
            tiers.add(new PlatformProfile.FrequencyTier(speed, idle, idle + (max - idle) * relativeDynamic));
        }
        return Collections.unmodifiableList(tiers);
    }

    private static PlatformProfile build(
            int vmCount, double storageRateMbPerSecond, double idleToMaxPowerRatio, boolean tiers) {
        requireRatio(idleToMaxPowerRatio);
        PlatformProfile.Builder builder = PlatformProfile.builder(tiers ? "standard-tiered" : "standard-plain")
                .storage(new PlatformProfile.StorageSpec(STORAGE_CAPACITY_MB, storageRateMbPerSecond))
                .costs(new PlatformProfile.CostSpec(0.0, 0.0, 0.0, 0.0));
        for (int i = 0; i < vmCount; i++) {
            double speed = mips(vmCount, i);
            double max = WATTS_PER_MIPS * speed;
            builder.addHost(
                    new PlatformProfile.HostSpec(i, 1, Math.ceil(speed), HOST_RAM_MB, HOST_BANDWIDTH, HOST_STORAGE_MB));
            builder.addVm(new PlatformProfile.VmSpec(
                    i,
                    speed,
                    1,
                    VM_RAM_MB,
                    VM_BANDWIDTH,
                    VM_IMAGE_MB,
                    "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                    new PlatformProfile.CostSpec(PRICE_PER_MIPS_SECOND * speed, 0.0, 0.0, 0.0),
                    tiers ? 0.0 : idleToMaxPowerRatio * max,
                    tiers ? 0.0 : max,
                    null,
                    tiers ? opteron2218Tiers(speed, idleToMaxPowerRatio) : null));
            builder.pinVmToHost(i, i);
        }
        return builder.build();
    }

    private static void requireRatio(double ratio) {
        if (!(ratio >= 0.0) || ratio > 1.0) {
            throw new IllegalArgumentException("Idle/max power ratio must lie in [0, 1]: " + ratio);
        }
    }
}
