package org.workflowsim.experiments.standard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.workflowsim.platform.PlatformProfile;

/**
 * Single source of truth for the standard-study platform constants.
 *
 * <p>STD-V1 (2026-11-25). Root cause: the historical FGCS platforms used speeds {@code 500 * ratio^i} (up to a
 * 16384x spread), four VMs against DAG widths of 12-3310, idle/max power 0.1 and non-linear prices, so their
 * comparisons were dominated by artefacts of the fleet rather than by the scheduling decision. This factory
 * declares a bounded, documented fleet:
 *
 * <ul>
 *   <li><b>Speeds</b>: {@code mips_i = 500 + 1500 * i / (N - 1)}, {@code i = 0..N-1}, so the slowest VM runs at
 *       {@value #MIN_MIPS} MIPS and the fastest at {@value #MAX_MIPS} MIPS for every {@code N}: the heterogeneity
 *       ratio max/min is exactly 4 independent of {@code N}, and the arithmetic mean speed is exactly
 *       {@code (500 + 2000) / 2 = 1250} MIPS ({@link #meanMips(int)}).</li>
 *   <li><b>Price</b>: linear, {@code cpuPerSecond = c * mips} with {@code c = }{@value #PRICE_PER_MIPS_SECOND}
 *       abstract units per MIPS-second, billed per simulated second of VM-charged CPU time (continuous,
 *       no rounding up to billing periods); the price per million instructions is therefore the same constant on
 *       every VM. Memory, storage, and bandwidth prices are zero. The unit is abstract, not a calibrated cloud
 *       price.</li>
 *   <li><b>Power</b>: linear utilisation model with {@code P_max = k * mips}, {@code k = }
 *       {@value #WATTS_PER_MIPS} W/MIPS (50 W at 500 MIPS, 200 W at 2000 MIPS) and {@code P_idle = r * P_max}
 *       with the idle/max ratio {@code r} a parameter, default {@value #DEFAULT_IDLE_TO_MAX_POWER_RATIO}. The
 *       default follows the measured idle draw of contemporary servers, about 60% of peak (Fan, Weber, Barroso,
 *       "Power provisioning for a warehouse-sized computer", ISCA 2007), and the 70% figure used by Beloglazov,
 *       Abawajy, Buyya, FGCS 28(5) 2012.</li>
 *   <li><b>Optional DVFS tiers</b>: the six voltage/frequency pairs of the AMD Opteron 2218 listed by Lee and
 *       Zomaya, "Energy conscious scheduling for distributed computing systems under different operating
 *       conditions", IEEE TPDS 22(8):1374-1381, 2011 ({@link #OPTERON_2218_VOLTS},
 *       {@link #OPTERON_2218_GHZ}). Tier {@code j} runs at {@code mips * f_j / f_0}; its busy power is
 *       {@code P_idle + (P_max - P_idle) * (V_j / V_0)^2 * (f_j / f_0)} (dynamic power proportional to
 *       {@code V^2 f}, DVFS design section 1.1); its idle power equals the default tier's (the platform parks
 *       at the default tier). The default tier equals the nominal MIPS, so every VM has default/nominal ratio 1
 *       and satisfies the uniform default-tier rule (FGCS-R5).</li>
 * </ul>
 *
 * <p>Each VM is pinned to its own single-PE host. The host PE capacity is {@code ceil(mips)} because the legacy
 * {@code Pe.getMips()} truncates to an integer (see R2-CAPACITY in {@code FgcPlatforms}); the VM keeps its exact
 * fractional rate.
 */
public final class StandardPlatforms {

    /** Slowest VM speed in MIPS. */
    public static final double MIN_MIPS = 500.0;

    /** Fastest VM speed in MIPS. */
    public static final double MAX_MIPS = 2000.0;

    /** Declared fleet sizes of the standard study. */
    public static final int[] STANDARD_VM_COUNTS = {8, 16, 32};

    /** Linear price constant {@code c}: abstract units per MIPS-second. */
    public static final double PRICE_PER_MIPS_SECOND = 1.0e-3;

    /** Maximum power per MIPS, watts. */
    public static final double WATTS_PER_MIPS = 0.1;

    /** Default idle-to-maximum power ratio (Fan et al. ISCA 2007). */
    public static final double DEFAULT_IDLE_TO_MAX_POWER_RATIO = 0.6;

    /** Supply voltages of the AMD Opteron 2218 levels 0..5 (Lee and Zomaya, TPDS 2011), volts. */
    public static final double[] OPTERON_2218_VOLTS = {1.30, 1.25, 1.20, 1.15, 1.10, 1.05};

    /** Frequencies of the AMD Opteron 2218 levels 0..5 (Lee and Zomaya, TPDS 2011), GHz. */
    public static final double[] OPTERON_2218_GHZ = {2.6, 2.4, 2.2, 2.0, 1.8, 1.0};

    static final int HOST_RAM_MB = 4096;
    static final long HOST_BANDWIDTH = 10_000L;
    static final long HOST_STORAGE_MB = 1_000_000L;
    static final int VM_RAM_MB = 512;
    static final long VM_BANDWIDTH = 1_000L;
    static final long VM_IMAGE_MB = 1_000L;
    static final long STORAGE_CAPACITY_MB = 1_000_000_000_000L;

    private StandardPlatforms() {}

    /**
     * Returns the speed of VM {@code index} in a fleet of {@code vmCount}.
     *
     * @param vmCount fleet size, at least 2
     * @param index zero-based VM index
     * @return {@code 500 + 1500 * index / (vmCount - 1)}
     */
    public static double mips(int vmCount, int index) {
        if (vmCount < 2 || index < 0 || index >= vmCount) {
            throw new IllegalArgumentException(
                    "Standard fleet requires at least 2 VMs and an index in range: N=" + vmCount + " i=" + index);
        }
        return MIN_MIPS + (MAX_MIPS - MIN_MIPS) * index / (vmCount - 1);
    }

    /**
     * Returns the arithmetic mean speed, summed in ascending VM order.
     *
     * @param vmCount fleet size
     * @return mean MIPS (1250 up to binary64 summation error)
     */
    public static double meanMips(int vmCount) {
        double sum = 0.0;
        for (int i = 0; i < vmCount; i++) {
            sum += mips(vmCount, i);
        }
        return sum / vmCount;
    }

    /**
     * Builds a plain-power fleet.
     *
     * @param vmCount fleet size, at least 2
     * @param storageRateMbPerSecond SHARED storage rate (decimal MB/s), finite positive
     * @param idleToMaxPowerRatio {@code P_idle / P_max} in {@code [0, 1]}
     * @return platform profile
     */
    public static PlatformProfile plain(int vmCount, double storageRateMbPerSecond, double idleToMaxPowerRatio) {
        return build(vmCount, storageRateMbPerSecond, idleToMaxPowerRatio, false);
    }

    /**
     * Builds the same fleet with the Opteron 2218 tier table on every VM.
     *
     * @param vmCount fleet size, at least 2
     * @param storageRateMbPerSecond SHARED storage rate (decimal MB/s), finite positive
     * @param idleToMaxPowerRatio {@code P_idle / P_max} in {@code [0, 1]}
     * @return platform profile
     */
    public static PlatformProfile tiered(int vmCount, double storageRateMbPerSecond, double idleToMaxPowerRatio) {
        return build(vmCount, storageRateMbPerSecond, idleToMaxPowerRatio, true);
    }

    /**
     * Returns the Opteron 2218 tier table of one VM, default (fastest) tier first.
     *
     * @param nominalMips VM nominal speed
     * @param idleToMaxPowerRatio idle/max power ratio
     * @return six strictly descending tiers
     */
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
