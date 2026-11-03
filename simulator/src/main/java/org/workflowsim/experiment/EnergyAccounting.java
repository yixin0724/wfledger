package org.workflowsim.experiment;

import java.util.LinkedHashMap;
import java.util.Map;

import org.workflowsim.platform.PlatformProfile;

/**
 * Per-VM energy integral and total-energy computation for the energy dimension
 * (BAT-E1, 2026-10-27).
 *
 * <p>Conservation law (Beloglazov et al. FGCS 2012 Eq.(2), binary specialization for
 * SPACE_SHARED one-Job-per-VM): each VM's instantaneous utilization is either 0 (idle)
 * or 1 (active), so the power integral collapses to two rectangles:</p>
 * <pre>
 * E_vm = P_idle × (T_sim − T_active) + P_max × T_active
 *      = P_idle × T_sim + (P_max − P_idle) × T_active
 * </pre>
 * <p>where T_sim = makespanSeconds and T_active = the VM's modeled busy-interval seconds
 * already present in every manifest's VmMetrics. This class exists to keep the 77-arg
 * SimulationMetrics constructor unchanged: the energy fields attach from outside.</p>
 *
 * <p>When no VM declares power fields, all values are zero — a legally declared
 * backward-compatible run. BAT-D2 (2026-10-29) extends the tiered branch with the
 * per-tier busy split derived from each Job's dispatch-time tier assignment; the
 * D1 surface (all-tiers-at-default) is preserved when per-tier attribution is
 * absent.</p>
 */
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

    /**
     * Floating-point sum tolerance (seconds) between the per-tier attribution and the
     * total busy seconds; summing the same ledger differs only in the last digits of the
     * nonlinear addition.
     */
    private static final double TIER_SUM_SLACK_SECONDS = 1e-9;

    /**
     * Computes per-VM and total energy from the frozen metrics and the platform
     * profile's power fields (D1 surface: all busy time at the default tier).
     */
    public static EnergyAccounting fromFrozenMetrics(
            double makespanSeconds,
            Map<Integer, ? extends SimulationMetrics.VmMetrics> vmMetrics,
            PlatformProfile platform) {
        return fromFrozenMetrics(
                makespanSeconds, vmMetrics, java.util.Collections.<Integer, Map<Double, Double>>emptyMap(), platform);
    }

    /**
     * Computes per-VM and total energy with the BAT-D2 (2026-10-29) per-tier split.
     *
     * <p>Extended conservation law: {@code E_vm = P_idle(default)×T_sim + Σ_tier
     * [(P_max(tier) − P_idle(default)) × busy(tier)]}. When the per-tier busy map
     * carries no entry for a tiered VM (v1/JUnit direct calls with tierless
     * attribution), the D1 degenerate behavior stays: the WHOLE busy interval is
     * accounted at the default tier. When tier data exists, every attributed tier
     * must match a declared tier of that VM by exact MIPS identity, and the
     * per-tier busy sum must cover the VM's total modeled busy interval — any gap
     * or overlap means the conservation evidence chain is broken and fails
     * explicitly rather than silently under-counting joules.</p>
     *
     * @param makespanSeconds simulation end seconds (T_sim of the conservation law)
     * @param vmMetrics frozen per-VM metrics carrying total busy intervals
     * @param perTierBusySecondsByVm per-VM per-tier busy seconds (BAT-D2 ledger);
     *        empty map re-enters the D1 all-at-default accounting
     * @param platform the platform profile carrying per-VM power and tier tables
     */
    public static EnergyAccounting fromFrozenMetrics(
            double makespanSeconds,
            Map<Integer, ? extends SimulationMetrics.VmMetrics> vmMetrics,
            Map<Integer, Map<Double, Double>> perTierBusySecondsByVm,
            PlatformProfile platform) {
        Map<Integer, Double> perVm = new LinkedHashMap<Integer, Double>();
        double total = 0.0;
        double peak = 0.0;
        for (Map.Entry<Integer, ? extends SimulationMetrics.VmMetrics> entry : vmMetrics.entrySet()) {
            int vmId = entry.getKey();
            SimulationMetrics.VmMetrics m = entry.getValue();
            PlatformProfile.VmSpec spec = findSpec(platform, vmId);
            // BAT-D2 (2026-10-29): fixes the D1 dead gate -- the power of a tiered VM lives
            // in the frequency tier table (the flat idle/max fields are forced to 0 by the
            // "single source of truth" validation), so hasPowerModel() is always false for
            // it, causing a platform that declares frequency tiers to be booked as 0 J
            // across the board as if it had no power model. The power gate now reads
            // "either the flat fields or the frequency tier table declares power" (the two
            // paths are mutually exclusive by VmSpec constructor validation).
            if (spec == null || !(spec.hasPowerModel() || spec.hasFrequencyTiers())) {
                perVm.put(vmId, Double.valueOf(0.0));
                continue;
            }
            double e;
            if (spec.hasFrequencyTiers()) {
                // v2: E_vm = P_idle(default)*T_sim + sum over tiers
                // [(P_max(tier) - P_idle(default))*busy(tier)]
                // -- algebraically identical to the formula corrected from the literature in
                // section 2.3 (P_idle*T_idle + sum over tiers P_max(tier)*busy(tier)).
                double idleDefault = spec.defaultFrequencyTier().getIdlePowerWatts();
                e = idleDefault * makespanSeconds;
                Map<Double, Double> tierBusy =
                        perTierBusySecondsByVm == null ? null : perTierBusySecondsByVm.get(Integer.valueOf(vmId));
                if (tierBusy == null || tierBusy.isEmpty()) {
                    // D1 degenerate accounting: no per-tier attribution (tierMips entirely
                    // absent) -> the whole interval as a default-tier full-load rectangle.
                    double dynDefault = spec.defaultFrequencyTier().getMaxPowerWatts() - idleDefault;
                    e += dynDefault * m.getModeledBusyIntervalSeconds();
                } else {
                    double attributed = 0.0;
                    // Ascending TreeMap key order = deterministic summation order (AGENTS #9).
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
                peak += spec.defaultFrequencyTier().getMaxPowerWatts();
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
     * BAT-D2: locates the tier by exact MIPS identity in the VM frequency tier table
     * (tiers are unique by MIPS; the descending-order check is already guaranteed at
     * VmSpec construction time). No match = broken evidence chain, explicit failure.
     */
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

    // BAT-R6 (2026-10-27, sixth review round): removed the never-called unpowered()
    // factory -- fromFrozenMetrics already yields all zeros for a platform without a power
    // model (a 0.0 contribution per VM), so the dead public surface had no caller since E1
    // landed; keeping a public surface without an owner is an unreviewed interface.

    private static PlatformProfile.VmSpec findSpec(PlatformProfile platform, int vmId) {
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.getId() == vmId) {
                return spec;
            }
        }
        return null;
    }

    /** @return total energy across all VMs in joules */
    public double getTotalEnergyJoules() {
        return totalEnergyJoules;
    }

    /** @return arithmetic mean of per-VM energy in joules */
    public double getMeanVmEnergyJoules() {
        return meanVmEnergyJoules;
    }

    /** @return fleet peak power (sum of per-VM max) in watts; 0 when no power model */
    public double getPeakPowerWatts() {
        return peakPowerWatts;
    }

    /** @return energy-delay product: totalEnergyJoules × makespanSeconds */
    public double getEnergyMakespanProductJouleSeconds() {
        return energyMakespanProductJouleSeconds;
    }

    /** @return unmodifiable per-VM energy map */
    public Map<Integer, Double> getVmEnergyJoules() {
        return vmEnergyJoules;
    }
}
