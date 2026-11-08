package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;

/**
 * Two carbon-dimension accountings (BAT-C1, 2026-10-28), both riding on energy quantities that
 * already passed the conservation laws. The models are not interchangeable
 * (docs/advanced/CARBON_DIMENSION_DESIGN.md v1 section 1):
 *
 * <p><b>Intensity model (CASPER family)</b>: gCO2 = energy x declared carbon intensity per VM.
 * The dimensional self-check can be carried out by hand:
 * {@code grams_vm = E_vm[J] / 3.6e6 * I_vm[g/kWh]} (kWh = J/3.6e6).</p>
 *
 * <p><b>Budget model (CWM/CaWoSched family)</b>: the carbon cost at time t is the excess of the
 * fleet power over the green power of that window; CC = sum over windows of
 * integral max(0, P_fleet(t) - green) dt. The fleet timeline is rebuilt from the compute Job
 * intervals of the report (per-VM union -- the retry-interval union semantics match the energy
 * chain), and power = sum idle + active VM count * per-VM dynamic power (v1 assumes uniform fleet
 * dynamic power, see the honesty boundary). Only the two CAWOSCHED planning labels instead use the
 * configured deadline horizon and each active Job's own flat-VM dynamic delta, matching their
 * timed-planner objective; non-CaWoSched reports preserve the v1 convention.</p>
 */
public final class CarbonAccounting {

    private final double totalCarbonGrams;
    private final double meanVmCarbonGrams;
    private final double carbonMakespanProductGramSeconds;
    private final Map<Integer, Double> perVmCarbonGrams;
    private final double totalCarbonExcessWattSeconds;
    private final Map<Integer, Double> windowExcessWattSeconds;

    private CarbonAccounting(
            double totalCarbonGrams,
            double meanVmCarbonGrams,
            double carbonMakespanProductGramSeconds,
            Map<Integer, Double> perVmCarbonGrams,
            double totalCarbonExcessWattSeconds,
            Map<Integer, Double> windowExcessWattSeconds) {
        this.totalCarbonGrams = totalCarbonGrams;
        this.meanVmCarbonGrams = meanVmCarbonGrams;
        this.carbonMakespanProductGramSeconds = carbonMakespanProductGramSeconds;
        this.perVmCarbonGrams = perVmCarbonGrams;
        this.totalCarbonExcessWattSeconds = totalCarbonExcessWattSeconds;
        this.windowExcessWattSeconds = windowExcessWattSeconds;
    }

    /**
     * @return intensity model total gCO2; 0 when that model is not declared (governed by whether
     *         the JSON contains that section)
     */
    public double getTotalCarbonGrams() {
        return totalCarbonGrams;
    }

    /** @return intensity model gCO2 per VM; an empty map when not declared */
    public Map<Integer, Double> getPerVmCarbonGrams() {
        return perVmCarbonGrams;
    }

    /** @return intensity model per-VM mean */
    public double getMeanVmCarbonGrams() {
        return meanVmCarbonGrams;
    }

    /** @return carbon-times-duration product in g*s (intensity model) */
    public double getCarbonMakespanProductGramSeconds() {
        return carbonMakespanProductGramSeconds;
    }

    /** @return budget model excess power integral in W*s; 0 while within budget */
    public double getTotalCarbonExcessWattSeconds() {
        return totalCarbonExcessWattSeconds;
    }

    /** @return budget model excess per window in W*s, keyed by window index */
    public Map<Integer, Double> getWindowExcessWattSeconds() {
        return windowExcessWattSeconds;
    }

    /**
     * Intensity model. Requires every VM to declare power (flat field <b>or</b> frequency tier
     * table -- the BAT-D1 single source of truth with two mutually exclusive paths, any
     * declaration means powered) and carbon intensity -- a half declaration fails fast (a
     * contract violation, not a zero contribution).
     *
     * <p>BAT-D4 (2026-10-29) tiered ride-through closure: the gram value multiplies the
     * <b>already frozen per-VM conserved energy</b> directly (EnergyAccounting recomputes it under
     * the flat/per-tier conservation laws, and SimulationReport constructs energy before carbon)
     * -- the intensity model does not open a second energy recompute surface (the same
     * multiplication, the same E source, mirroring the "intensity times audited E" convention of
     * the Python auditor verify-study-evidence.py, docs/advanced/DVFS_DIMENSION_DESIGN.md
     * section 1.5/2.4). On a flat platform the bitwise result is identical to the previous inline
     * form (the same expression, the same summation order); on a tiered platform E is the
     * per-tier conservation value -- that path previously failed the whole section fast for tiered
     * runs (tiered VM flat fields are constantly 0 and were rejected by the BAT-C1 gate), and this
     * batch closes the forward gap noted in the D3 design note: tiered platforms are reachable for
     * real carbonMetrics from now on.</p>
     *
     * @param profile platform profile (the source of truth for the power/tier/intensity
     *        declarations)
     * @param verifiedEnergy per-VM energy already frozen under the conservation laws (output of the
     *        same-package EnergyAccounting)
     * @param makespanSeconds simulation end second (used for the carbon-times-duration product)
     */
    public static CarbonAccounting intensityModel(
            PlatformProfile profile, EnergyAccounting verifiedEnergy, double makespanSeconds) {
        Map<Integer, Double> perVm = new LinkedHashMap<Integer, Double>();
        double total = 0.0;
        for (Map.Entry<Integer, Double> entry :
                verifiedEnergy.getVmEnergyJoules().entrySet()) {
            int vmId = entry.getKey();
            PlatformProfile.VmSpec spec = findSpec(profile, vmId);
            if (spec == null
                    || !(spec.hasPowerModel() || spec.hasFrequencyTiers())
                    || spec.getCarbonIntensityGramsPerKWh() == null) {
                throw new IllegalArgumentException("Intensity carbon model requires powered VMs with declared"
                        + " carbonIntensityGramsPerKWh on every VM; VM " + vmId + " violates this (BAT-C1)");
            }
            double grams = entry.getValue().doubleValue()
                    / 3.6e6
                    * spec.getCarbonIntensityGramsPerKWh().doubleValue();
            perVm.put(vmId, grams);
            total += grams;
        }
        double mean = perVm.isEmpty() ? 0.0 : total / perVm.size();
        return new CarbonAccounting(total, mean, total * makespanSeconds, perVm, 0.0, Collections.emptyMap());
    }

    /**
     * Budget model. Rebuilds the per-VM busy union from the compute Job intervals of the report,
     * then the fleet concurrency curve, then the per-window excess integral. Following the v1
     * constraint, the fleet dynamic power is the delta P of the first VM (uniform fleet, the
     * honesty boundary is declared). CAWOSCHED_SLACK/PRESSURE take the separate full-deadline,
     * heterogeneous-flat-VM compatibility branch below; all other labels stay on this v1 path.
     */
    public static CarbonAccounting budgetModel(SimulationReport report, PlatformProfile profile) {
        List<PlatformProfile.CarbonWindow> windows = profile.getCarbonWindows();
        if (windows == null || windows.isEmpty()) {
            throw new IllegalArgumentException("Budget carbon model requires declared carbonWindows (BAT-C1)");
        }
        double makespan = report.getMakespan();
        // A2 (2026-11-02): a tiered platform counts as declared power (mirrors the BAT-D2 power
        // gate). If any VM carries a tier table, the per-tier fleet-power curve is used; otherwise
        // the v1 convention stays <b>bitwise unchanged</b> (the rerunnability of the frozen carbon
        // baseline depends on it -- the two branches use different power algebra and cannot be
        // merged).
        boolean anyTiered = false;
        for (PlatformProfile.VmSpec spec : profile.getVms()) {
            if (spec.hasFrequencyTiers()) {
                anyTiered = true;
                break;
            }
        }
        if (anyTiered) {
            return budgetModelWithTiers(report, profile, windows);
        }
        Parameters.PlanningAlgorithm planning = report.getConfig().getPlanningAlgorithm();
        if (planning == Parameters.PlanningAlgorithm.CAWOSCHED_SLACK
                || planning == Parameters.PlanningAlgorithm.CAWOSCHED_PRESSURE) {
            return budgetModelCaWoSchedFlat(report, profile, windows);
        }
        double idleSum = 0.0;
        double dynamicPerVm = 0.0;
        boolean firstVm = true;
        for (PlatformProfile.VmSpec spec : profile.getVms()) {
            if (!spec.hasPowerModel()) {
                throw new IllegalArgumentException("Budget carbon model requires powered VMs (BAT-C1)");
            }
            idleSum += spec.getIdlePowerWatts();
            // BAT-C2 (2026-10-28 cross-language alignment): the fleet dynamic power takes the
            // max - idle of the <b>first</b> VM -- v1 assumes uniform fleet dynamic power (design
            // document section 2.1, honesty boundary); the loop body previously overwrote it per
            // VM, so the last VM took effect and drifted on heterogeneous fleets from the first-VM
            // convention documented in the Python auditor (verify-study-evidence.py), violating
            // the discipline that the Java and Python audit dimensions must align; both sides are
            // now unified.
            if (firstVm) {
                dynamicPerVm = spec.getMaxPowerWatts() - spec.getIdlePowerWatts();
                firstVm = false;
            }
        }
        // Event folding: the time endpoints of the per-VM busy union -> the fleet curve of the
        // active VM count.
        TreeMap<Double, Integer> ends = new TreeMap<Double, Integer>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() != 2) {
                continue; // Only compute Jobs define busy intervals (stage-in does not take the
                // SPACE_SHARED execution interval)
            }
            double a = job.getStartTime();
            double b = job.getFinishTime();
            if (b > a) {
                bump(ends, a, 1);
                bump(ends, b, -1);
            }
        }
        // Piecewise fleet power (the concurrency count is constant within a segment).
        List<double[]> segments = new ArrayList<double[]>();
        double concurrent = 0.0;
        double prev = 0.0;
        for (Map.Entry<Double, Integer> e : ends.entrySet()) {
            double t = e.getKey().doubleValue();
            if (t > prev) {
                segments.add(new double[] {prev, t, idleSum + concurrent * dynamicPerVm});
            }
            concurrent += e.getValue().intValue();
            prev = t;
        }
        if (prev < makespan) {
            segments.add(new double[] {prev, makespan, idleSum + concurrent * dynamicPerVm});
        }
        // Per-window excess integral.
        Map<Integer, Double> windowExcess = new LinkedHashMap<Integer, Double>();
        double total = 0.0;
        for (int j = 0; j < windows.size(); j++) {
            PlatformProfile.CarbonWindow w = windows.get(j);
            double acc = 0.0;
            for (double[] seg : segments) {
                double lo = Math.max(seg[0], w.getStartSeconds());
                double hi = Math.min(seg[1], w.getEndSeconds());
                if (hi > lo) {
                    acc += Math.max(0.0, seg[2] - w.getGreenWatts()) * (hi - lo);
                }
            }
            if (acc > 0.0) {
                windowExcess.put(Integer.valueOf(j), acc);
                total += acc;
            }
        }
        return new CarbonAccounting(0.0, 0.0, 0.0, Collections.emptyMap(), total, windowExcess);
    }

    /**
     * CaWoSched flat-fleet compatibility: integrate the configured deadline horizon and fold each
     * active Job by its own VM's max-idle dynamic watts. Other planners retain the historical
     * first-VM uniform-delta/makespan branch above; the tiered branch is likewise unchanged.
     */
    private static CarbonAccounting budgetModelCaWoSchedFlat(
            SimulationReport report, PlatformProfile profile, List<PlatformProfile.CarbonWindow> windows) {
        double horizon = report.getConfig().getDeadline();
        if (!(horizon > 0.0) || !Double.isFinite(horizon)) {
            throw new IllegalArgumentException("CaWoSched budget accounting requires a positive deadline horizon");
        }
        double idleSum = 0.0;
        for (PlatformProfile.VmSpec spec : profile.getVms()) {
            if (!spec.hasPowerModel()) {
                throw new IllegalArgumentException("Budget carbon model requires powered VMs (BAT-C1)");
            }
            idleSum += spec.getIdlePowerWatts();
        }
        TreeMap<Double, Double> ends = new TreeMap<Double, Double>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() != 2) {
                continue;
            }
            double start = job.getStartTime();
            double finish = job.getFinishTime();
            if (finish <= start) {
                continue;
            }
            PlatformProfile.VmSpec spec = vmSpec(profile, job.getVmId());
            if (spec == null) {
                throw new IllegalArgumentException("Job " + job.getJobId() + " references undeclared VM "
                        + job.getVmId() + ": the CaWoSched fleet-power curve cannot be built");
            }
            double dynamic = spec.getMaxPowerWatts() - spec.getIdlePowerWatts();
            bumpDouble(ends, start, dynamic);
            bumpDouble(ends, finish, -dynamic);
        }
        List<double[]> segments = new ArrayList<double[]>();
        double active = 0.0;
        double previous = 0.0;
        for (Map.Entry<Double, Double> entry : ends.entrySet()) {
            double time = entry.getKey().doubleValue();
            if (time >= horizon) {
                break;
            }
            if (time > previous) {
                segments.add(new double[] {previous, time, idleSum + active});
            }
            active += entry.getValue().doubleValue();
            previous = time;
        }
        if (previous < horizon) {
            segments.add(new double[] {previous, horizon, idleSum + active});
        }
        Map<Integer, Double> windowExcess = new LinkedHashMap<Integer, Double>();
        double total = 0.0;
        for (int index = 0; index < windows.size(); index++) {
            PlatformProfile.CarbonWindow window = windows.get(index);
            double acc = 0.0;
            for (double[] segment : segments) {
                double lo = Math.max(segment[0], window.getStartSeconds());
                double hi = Math.min(segment[1], window.getEndSeconds());
                if (hi > lo) {
                    acc += Math.max(0.0, segment[2] - window.getGreenWatts()) * (hi - lo);
                }
            }
            if (acc > 0.0) {
                windowExcess.put(Integer.valueOf(index), Double.valueOf(acc));
                total += acc;
            }
        }
        return new CarbonAccounting(0.0, 0.0, 0.0, Collections.emptyMap(), total, windowExcess);
    }

    /**
     * A2 (2026-11-02): budget carbon for tiered platforms -- the per-tier fleet-power curve.
     *
     * <p>Contract (DVFS_DIMENSION_DESIGN.md section 1.5 "per-tier fleet-power curve",
     * section 2.3): at every instant
     * {@code P_fleet(t) = sum over all VMs of P_idle(default tier) + sum over active Jobs
     * of (P_max(its tier) - P_idle(default tier))}. The static component of a tiered VM takes
     * the idle power of the <b>default tier (the first tier in the table)</b>, sharing its source
     * with the conservation law
     * {@code E_vm = P_idle(default)*T_sim + sum(P_max(tier)-P_idle(default))*busy(tier)}; a flat VM
     * keeps the v1 {@code max-idle} contribution (a mixed fleet is legal). The tier identity is
     * the {@code tierMips} carried by the Job (the same attribution already checked by the
     * per-tier conservation chain); a missing tier falls back to the default tier.</p>
     *
     * <p>Mirrors the tiered budget branch of {@code scripts/verify-study-evidence.py} sentence by
     * sentence -- the Java and Python audit conventions must align (AGENTS.md rule 10).</p>
     */
    private static CarbonAccounting budgetModelWithTiers(
            SimulationReport report, PlatformProfile profile, List<PlatformProfile.CarbonWindow> windows) {
        double makespan = report.getMakespan();
        double idleSum = 0.0;
        for (PlatformProfile.VmSpec spec : profile.getVms()) {
            idleSum += spec.hasFrequencyTiers()
                    ? spec.getFrequencyTiers().get(0).getIdlePowerWatts()
                    : spec.getIdlePowerWatts();
        }
        // Endpoint folding: +/- the dynamic watts of that Job (not a +/-1 count -- the dynamic
        // component differs between tiers).
        TreeMap<Double, Double> ends = new TreeMap<Double, Double>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() != 2) {
                continue; // Only compute Jobs define busy intervals (same convention as the v1 branch)
            }
            double a = job.getStartTime();
            double b = job.getFinishTime();
            if (b <= a) {
                continue;
            }
            PlatformProfile.VmSpec spec = vmSpec(profile, job.getVmId());
            if (spec == null) {
                throw new IllegalArgumentException("Job " + job.getJobId() + " references undeclared VM "
                        + job.getVmId() + ": the fleet-power curve cannot be built");
            }
            double dynamic = jobDynamicWatts(spec, job.getJobId(), job.getTierMips());
            bumpDouble(ends, a, dynamic);
            bumpDouble(ends, b, -dynamic);
        }
        List<double[]> segments = new ArrayList<double[]>();
        double active = 0.0;
        double prev = 0.0;
        for (Map.Entry<Double, Double> entry : ends.entrySet()) {
            double t = entry.getKey().doubleValue();
            if (t > prev) {
                segments.add(new double[] {prev, t, idleSum + active});
            }
            active += entry.getValue().doubleValue();
            prev = t;
        }
        if (prev < makespan) {
            segments.add(new double[] {prev, makespan, idleSum + active});
        }
        Map<Integer, Double> windowExcess = new LinkedHashMap<Integer, Double>();
        double total = 0.0;
        for (int index = 0; index < windows.size(); index++) {
            PlatformProfile.CarbonWindow window = windows.get(index);
            double acc = 0.0;
            for (double[] segment : segments) {
                double lo = Math.max(segment[0], window.getStartSeconds());
                double hi = Math.min(segment[1], window.getEndSeconds());
                if (hi > lo) {
                    acc += Math.max(0.0, segment[2] - window.getGreenWatts()) * (hi - lo);
                }
            }
            if (acc > 0.0) {
                windowExcess.put(Integer.valueOf(index), Double.valueOf(acc));
                total += acc;
            }
        }
        return new CarbonAccounting(0.0, 0.0, 0.0, Collections.emptyMap(), total, windowExcess);
    }

    /**
     * A2 (2026-11-02): the dynamic share of a single job on the fleet-power curve.
     *
     * <p>Tiered VM: {@code P_max(its tier) - P_idle(default tier)}, a missing tier falls back to
     * the default tier; flat VM: {@code max - idle} (the v1 contribution). <b>Cross-language
     * alignment</b>: a job carrying {@code tierMips} on a flat VM is a broken evidence chain --
     * the Python auditor rejects it as well ("job on non-tiered VM carries tierMips"), and the
     * two sides must not reach different conclusions on the same evidence.</p>
     */
    static double jobDynamicWatts(PlatformProfile.VmSpec spec, int jobId, Double tierMips) {
        if (spec.hasFrequencyTiers()) {
            double defaultIdle = spec.getFrequencyTiers().get(0).getIdlePowerWatts();
            double mips = tierMips == null ? spec.getFrequencyTiers().get(0).getMips() : tierMips.doubleValue();
            return tierMaxWatts(spec, mips) - defaultIdle;
        }
        if (tierMips != null) {
            throw new IllegalArgumentException("Job " + jobId + " on non-tiered VM " + spec.getId()
                    + " carries tierMips " + tierMips + ": the per-Job tier evidence chain is broken (BAT-D3)");
        }
        return spec.getMaxPowerWatts() - spec.getIdlePowerWatts();
    }

    /**
     * Returns the tier peak power by exact MIPS identity (the tier table is unique in descending
     * order; the same identity rule as the per-tier conservation chain).
     */
    private static double tierMaxWatts(PlatformProfile.VmSpec spec, double tierMips) {
        for (PlatformProfile.FrequencyTier tier : spec.getFrequencyTiers()) {
            if (Double.compare(tier.getMips(), tierMips) == 0) {
                return tier.getMaxPowerWatts();
            }
        }
        throw new IllegalArgumentException("Job tier mips " + tierMips + " matches no declared tier of VM "
                + spec.getId() + ": the per-Job tier evidence chain is broken (BAT-D2)");
    }

    private static PlatformProfile.VmSpec vmSpec(PlatformProfile profile, int vmId) {
        for (PlatformProfile.VmSpec spec : profile.getVms()) {
            if (spec.getId() == vmId) {
                return spec;
            }
        }
        return null;
    }

    private static void bumpDouble(TreeMap<Double, Double> map, double key, double delta) {
        Double boxed = Double.valueOf(key);
        Double current = map.get(boxed);
        map.put(boxed, Double.valueOf((current == null ? 0.0 : current.doubleValue()) + delta));
    }

    private static PlatformProfile.VmSpec findSpec(PlatformProfile profile, int vmId) {
        for (PlatformProfile.VmSpec spec : profile.getVms()) {
            if (spec.getId() == vmId) {
                return spec;
            }
        }
        return null;
    }

    private static void bump(TreeMap<Double, Integer> map, double key, int delta) {
        Double boxed = Double.valueOf(key);
        Integer current = map.get(boxed);
        map.put(boxed, Integer.valueOf((current == null ? 0 : current.intValue()) + delta));
    }
}
