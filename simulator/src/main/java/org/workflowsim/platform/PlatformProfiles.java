package org.workflowsim.platform;

/** Set of standardized, explicit abstract platforms used by the maintained examples. */
public final class PlatformProfiles {

    private PlatformProfiles() {}

    /**
     * Creates a homogeneous local abstract platform with one single-core VM per
     * Host.
     *
     * <p>This platform is a configuration-driven research baseline, not a
     * replay of a WfCommons execution machine.</p>
     *
     * @param name platform name
     * @param vmCount number of VMs and Hosts to create, must be positive
     * @return homogeneous abstract platform with one VM per Host
     * @throws IllegalArgumentException when {@code vmCount} is not positive
     */
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

    /**
     * Creates a local abstract platform with heterogeneous MIPS: one single-core
     * VM per Host, with VM IDs increasing from 0 in {@code vmMips} list order.
     *
     * <p>Used to compare scheduling behavior under different compute capacities.
     * Changing only MIPS does not make the cost term of the matrix-free PSO vary
     * with the mapping: {@code (rawLength / mips) * (mips / 1000) =
     * rawLength / 1000}. Once a task-by-VM cost matrix is configured, the
     * effective execution seconds need no longer be inversely proportional to
     * MIPS, so only then can the PSO cost term vary with the mapping; its unit
     * price remains an abstract heuristic, not real cloud pricing. The Host
     * per-PE MIPS is fixed at twice the corresponding VM, matching the Host/VM
     * ratio of {@link #homogeneousLocal(String, int)}; all other resource
     * specifications are identical.</p>
     *
     * <p>This platform is a configuration-driven research baseline, not a
     * machine-type replay of any real cloud.</p>
     *
     * @param name platform name
     * @param vmMips MIPS of each VM (must be positive and finite); list length
     *        = number of VMs/Hosts
     * @return heterogeneous abstract platform with one VM per Host
     * @throws IllegalArgumentException when the list is empty or contains a
     *         non-positive or non-finite MIPS value
     */
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
