package org.workflowsim.platform;

public final class PlatformProfiles {

    private PlatformProfiles() {}

    public static PlatformProfile homogeneousLocal(String name, int vmCount) {
        if (vmCount <= 0) {
            throw new IllegalArgumentException("VM count must be positive");
        }
        PlatformProfile.Builder builder = PlatformProfile.builder(name);
        for (int id = 0; id < vmCount; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2000.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    id, 1000.0, 1, 512, 1000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }

    public static PlatformProfile heterogeneousLocal(String name, java.util.List<Double> vmMips) {
        if (vmMips == null || vmMips.isEmpty()) {
            throw new IllegalArgumentException("VM MIPS list must be non-empty");
        }
        PlatformProfile.Builder builder = PlatformProfile.builder(name);
        for (int id = 0; id < vmMips.size(); id++) {
            Double mips = vmMips.get(id);
            if (mips == null || mips.isNaN() || mips.isInfinite() || mips <= 0.0) {
                throw new IllegalArgumentException("VM MIPS must be positive and finite: " + mips);
            }
            builder.addHost(new PlatformProfile.HostSpec(id, 2, mips * 2.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(
                    id, mips, 1, 512, 1000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }
}
