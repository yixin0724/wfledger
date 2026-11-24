package org.workflowsim.experiment;

import java.util.LinkedHashMap;
import java.util.Map;

import org.workflowsim.platform.PlatformProfile;

public final class EnergyAccounting {

    private final double totalEnergyJoules;
    private final double meanVmEnergyJoules;
    private final double peakPowerWatts;
    private final double energyMakespanProductJouleSeconds;
    private final Map<Integer, Double> vmEnergyJoules;

    private EnergyAccounting(
            double totalEnergyJoules,
            double meanVmEnergyJoules,
            double peakPowerWatts,
            double energyMakespanProductJouleSeconds,
            Map<Integer, Double> vmEnergyJoules) {
        this.totalEnergyJoules = totalEnergyJoules;
        this.meanVmEnergyJoules = meanVmEnergyJoules;
        this.peakPowerWatts = peakPowerWatts;
        this.energyMakespanProductJouleSeconds = energyMakespanProductJouleSeconds;
        this.vmEnergyJoules = vmEnergyJoules;
    }

    private static final double TIER_SUM_SLACK_SECONDS = 1e-9;

    public static EnergyAccounting fromFrozenMetrics(
            double makespanSeconds,
            Map<Integer, ? extends SimulationMetrics.VmMetrics> vmMetrics,
            PlatformProfile platform) {
        return fromFrozenMetrics(
                makespanSeconds, vmMetrics, java.util.Collections.<Integer, Map<Double, Double>>emptyMap(), platform);
    }

    public static EnergyAccounting fromFrozenMetrics(
            double makespanSeconds,
            Map<Integer, ? extends SimulationMetrics.VmMetrics> vmMetrics,
            Map<Integer, Map<Double, Double>> perTierBusySecondsByVm,
            PlatformProfile platform) {
        requireAllOrNonePowered(platform);
        Map<Integer, Double> perVm = new LinkedHashMap<Integer, Double>();
        double total = 0.0;
        double peak = 0.0;
        for (Map.Entry<Integer, ? extends SimulationMetrics.VmMetrics> entry : vmMetrics.entrySet()) {
            int vmId = entry.getKey();
            SimulationMetrics.VmMetrics m = entry.getValue();
            PlatformProfile.VmSpec spec = findSpec(platform, vmId);

            if (spec == null || !(spec.hasPowerModel() || spec.hasFrequencyTiers())) {
                perVm.put(vmId, Double.valueOf(0.0));
                continue;
            }
            double e;
            if (spec.hasFrequencyTiers()) {

                double idleDefault = spec.defaultFrequencyTier().getIdlePowerWatts();
                e = idleDefault * makespanSeconds;
                Map<Double, Double> tierBusy =
                        perTierBusySecondsByVm == null ? null : perTierBusySecondsByVm.get(Integer.valueOf(vmId));
                if (tierBusy == null || tierBusy.isEmpty()) {

                    double dynDefault = spec.defaultFrequencyTier().getMaxPowerWatts() - idleDefault;
                    e += dynDefault * m.getModeledBusyIntervalSeconds();
                } else {
                    double attributed = 0.0;

                    for (Map.Entry<Double, Double> tier : new java.util.TreeMap<Double, Double>(tierBusy).entrySet()) {
                        PlatformProfile.FrequencyTier byMips =
                                tierByMips(spec, tier.getKey().doubleValue());
                        e += (byMips.getMaxPowerWatts() - idleDefault)
                                * tier.getValue().doubleValue();
                        attributed += tier.getValue().doubleValue();
                    }
                    if (Math.abs(attributed - m.getModeledBusyIntervalSeconds()) > TIER_SUM_SLACK_SECONDS) {
                        throw new IllegalStateException("Per-tier busy seconds of VM " + vmId + " (sum="
                                + attributed + ") do not equal its total modeled busy interval ("
                                + m.getModeledBusyIntervalSeconds() + "): the per-tier conservation "
                                + "evidence chain is broken (BAT-D2)");
                    }
                }
                double nameplateMax = 0.0;
                for (PlatformProfile.FrequencyTier tier : spec.getFrequencyTiers()) {
                    nameplateMax = Math.max(nameplateMax, tier.getMaxPowerWatts());
                }
                peak += nameplateMax;
            } else {
                e = spec.getIdlePowerWatts() * makespanSeconds
                        + (spec.getMaxPowerWatts() - spec.getIdlePowerWatts()) * m.getModeledBusyIntervalSeconds();
                peak += spec.getMaxPowerWatts();
            }
            perVm.put(vmId, Double.valueOf(e));
            total += e;
        }
        double mean = perVm.isEmpty() ? 0.0 : total / perVm.size();
        return new EnergyAccounting(
                total, mean, peak, total * makespanSeconds, java.util.Collections.unmodifiableMap(perVm));
    }

    /**
     * FGCS-R3 (2026-11-24, A5): energy accounting is all-or-none. Root cause: a fleet mixing
     * powered VMs (plain power fields or frequency tiers) with unpowered VMs used to book the
     * unpowered VMs at 0 J, so {@code totalEnergyJoules} silently understated fleet energy and
     * looked like a legal measurement. A platform must declare a power source on every VM or
     * on none (ENERGY_DIMENSION_DESIGN.md §2.2).
     *
     * @param platform declared platform
     * @throws IllegalStateException when only part of the fleet declares a power model
     */
    public static void requireAllOrNonePowered(PlatformProfile platform) {
        Integer powered = null;
        Integer unpowered = null;
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            boolean hasPower = spec.hasPowerModel() || spec.hasFrequencyTiers();
            if (hasPower && powered == null) {
                powered = spec.getId();
            } else if (!hasPower && unpowered == null) {
                unpowered = spec.getId();
            }
        }
        if (powered != null && unpowered != null) {
            throw new IllegalStateException("Energy accounting requires a power model on every VM or on none:"
                    + " VM " + powered + " declares one but VM " + unpowered + " does not; booking the"
                    + " unpowered VM at 0 J would silently understate fleet energy (FGCS-R3 A5)");
        }
    }

    private static PlatformProfile.FrequencyTier tierByMips(PlatformProfile.VmSpec spec, double mips) {
        for (PlatformProfile.FrequencyTier tier : spec.getFrequencyTiers()) {
            if (Double.compare(tier.getMips(), mips) == 0) {
                return tier;
            }
        }
        throw new IllegalStateException("Job outcome carries tier mips " + mips
                + " which matches no declared frequency tier of VM " + spec.getId()
                + ": the per-Job tier evidence chain is broken (BAT-D2)");
    }

    private static PlatformProfile.VmSpec findSpec(PlatformProfile platform, int vmId) {
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.getId() == vmId) {
                return spec;
            }
        }
        return null;
    }

    public double getTotalEnergyJoules() {
        return totalEnergyJoules;
    }

    public double getMeanVmEnergyJoules() {
        return meanVmEnergyJoules;
    }

    public double getPeakPowerWatts() {
        return peakPowerWatts;
    }

    public double getEnergyMakespanProductJouleSeconds() {
        return energyMakespanProductJouleSeconds;
    }

    public Map<Integer, Double> getVmEnergyJoules() {
        return vmEnergyJoules;
    }
}
