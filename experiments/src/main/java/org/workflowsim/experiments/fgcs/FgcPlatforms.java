package org.workflowsim.experiments.fgcs;

import java.util.Arrays;
import java.util.Collections;

import org.workflowsim.data.v2.DataflowStorageSpec;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;

public final class FgcPlatforms {

    private FgcPlatforms() {}

    public static PlatformProfile flat(int vmCount, int speedRatio) {
        return create(vmCount, speedRatio, false, false, null, null, null);
    }

    public static PlatformProfile flat(int vmCount, int speedRatio, int storageRate) {
        return create(vmCount, speedRatio, false, false, null, null, Double.valueOf(storageRate));
    }

    public static PlatformProfile tiered(int vmCount, int speedRatio, boolean nonMonotonic) {
        return create(vmCount, speedRatio, true, nonMonotonic, null, null, null);
    }

    public static PlatformProfile fatTree(int vmCount, int speedRatio, int k, double bandwidth) {
        return create(vmCount, speedRatio, false, false, NetworkTopologySpec.fatTree(k, bandwidth), null, null);
    }

    public static PlatformProfile storage(int vmCount, int speedRatio) {
        return create(vmCount, speedRatio, false, false, null, DataflowStorageSpec.of(0, 100.0, 100.0, 100.0), null);
    }

    public static PlatformProfile storageFatTree(int vmCount, int speedRatio, int k, double bandwidth) {
        return create(
                vmCount,
                speedRatio,
                false,
                false,
                NetworkTopologySpec.fatTree(k, bandwidth),
                DataflowStorageSpec.of(0, 100.0, 100.0, 100.0),
                null);
    }

    private static PlatformProfile create(
            int vmCount,
            int speedRatio,
            boolean tiers,
            boolean nonMonotonic,
            NetworkTopologySpec topology,
            DataflowStorageSpec storage,
            Double storageRate) {
        if (vmCount < 1 || vmCount > 16 || speedRatio < 1) {
            throw new IllegalArgumentException("FGCS platform requires 1..16 VMs and a positive speed ratio");
        }
        PlatformProfile.Builder builder = PlatformProfile.builder("fgcs-platform")
                .storage(new PlatformProfile.StorageSpec(
                        1000000000L, storageRate == null ? 15 : storageRate.intValue()));
        double bandwidth = 100.0;
        for (int index = 0; index < vmCount; index++) {
            int id = index + 1;
            double mips = 500.0 * Math.pow(speedRatio, index);
            double idle = 5.0 * (index + 1);
            double max = 50.0 * (index + 1);
            builder.addHost(new PlatformProfile.HostSpec(index, 1, mips, 4096, 10000L, 1000000L));
            java.util.List<PlatformProfile.FrequencyTier> frequencyTiers = Collections.emptyList();
            double plainIdle = idle;
            double plainMax = max;
            if (tiers) {
                plainIdle = 0.0;
                plainMax = 0.0;
                frequencyTiers = Arrays.asList(
                        new PlatformProfile.FrequencyTier(mips, idle, max),
                        new PlatformProfile.FrequencyTier(
                                mips / 2.0, idle / 2.0, nonMonotonic ? max * 2.0 : max / 5.0));
            }
            builder.addVm(new PlatformProfile.VmSpec(
                    id,
                    mips,
                    1,
                    4096,
                    100L,
                    1000L,
                    "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                    new PlatformProfile.CostSpec(index + 1, 0.0, 0.0, 0.0),
                    plainIdle,
                    plainMax,
                    null,
                    frequencyTiers));
            builder.pinVmToHost(id, index);
        }
        if (topology != null) {
            builder.networkTopology(topology);
        }
        if (storage != null) {
            builder.sourceStorage(storage);
        }
        return builder.build();
    }
}
