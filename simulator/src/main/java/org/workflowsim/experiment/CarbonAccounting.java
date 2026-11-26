package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;

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

    public double getTotalCarbonGrams() {
        return totalCarbonGrams;
    }

    public Map<Integer, Double> getPerVmCarbonGrams() {
        return perVmCarbonGrams;
    }

    public double getMeanVmCarbonGrams() {
        return meanVmCarbonGrams;
    }

    public double getCarbonMakespanProductGramSeconds() {
        return carbonMakespanProductGramSeconds;
    }

    public double getTotalCarbonExcessWattSeconds() {
        return totalCarbonExcessWattSeconds;
    }

    public Map<Integer, Double> getWindowExcessWattSeconds() {
        return windowExcessWattSeconds;
    }

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

    public static CarbonAccounting budgetModel(SimulationReport report, PlatformProfile profile) {
        List<PlatformProfile.CarbonWindow> windows = profile.getCarbonWindows();
        if (windows == null || windows.isEmpty()) {
            throw new IllegalArgumentException("Budget carbon model requires declared carbonWindows (BAT-C1)");
        }
        double makespan = report.getMakespan();

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

            if (firstVm) {
                dynamicPerVm = spec.getMaxPowerWatts() - spec.getIdlePowerWatts();
                firstVm = false;
            }
        }

        TreeMap<Double, Integer> ends = new TreeMap<Double, Integer>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() != 2) {
                continue;
            }
            double a = job.getStartTime();
            double b = job.getFinishTime();
            if (b > a) {
                bump(ends, a, 1);
                bump(ends, b, -1);
            }
        }

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

    private static CarbonAccounting budgetModelWithTiers(
            SimulationReport report, PlatformProfile profile, List<PlatformProfile.CarbonWindow> windows) {
        double makespan = report.getMakespan();
        double idleSum = 0.0;
        for (PlatformProfile.VmSpec spec : profile.getVms()) {
            idleSum += spec.hasFrequencyTiers()
                    ? spec.getFrequencyTiers().get(0).getIdlePowerWatts()
                    : spec.getIdlePowerWatts();
        }

        TreeMap<Double, Double> ends = new TreeMap<Double, Double>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() != 2) {
                continue;
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

    static double jobDynamicWatts(PlatformProfile.VmSpec spec, int jobId, Double tierMips) {
        if (spec.hasFrequencyTiers()) {
            double defaultIdle = spec.getFrequencyTiers().get(0).getIdlePowerWatts();
            double mips = tierMips == null ? spec.getFrequencyTiers().get(0).getMips() : tierMips.doubleValue();
            return tierMaxWatts(spec, mips) - defaultIdle;
        }
        if (tierMips != null) {
            throw new IllegalArgumentException("Job " + jobId + " on non-tiered VM " + spec.getId()
                    + " carries tierMips " + tierMips + ": the per-Job tier evidence chain is broken");
        }
        return spec.getMaxPowerWatts() - spec.getIdlePowerWatts();
    }

    private static double tierMaxWatts(PlatformProfile.VmSpec spec, double tierMips) {
        for (PlatformProfile.FrequencyTier tier : spec.getFrequencyTiers()) {
            if (Double.compare(tier.getMips(), tierMips) == 0) {
                return tier.getMaxPowerWatts();
            }
        }
        throw new IllegalArgumentException("Job tier mips " + tierMips + " matches no declared tier of VM "
                + spec.getId() + ": the per-Job tier evidence chain is broken");
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
