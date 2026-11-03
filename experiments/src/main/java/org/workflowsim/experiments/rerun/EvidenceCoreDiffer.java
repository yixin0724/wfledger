package org.workflowsim.experiments.rerun;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.workflowsim.experiments.common.ExactJsonValues;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * D2 stage 5: bitwise core-quantity divergence comparator.
 *
 * <p>The contract (docs/experiments/RERUN_DIFF_CONTRACT.md, section "Comparison Rules") covers
 * three field classes:</p>
 * <ul>
 *   <li><b>Core quantities</b> -- every manifest field outside the explicit exemptions, every
 *       metrics field (except the two wall-clock nanosecond totals), every events field
 *       (except the local elapsed-time keys), and every field of the optional network ledger.
 *       Comparison is the <b>exact equality</b> of the serialized values; floating point gets
 *       no epsilon: every case that would need a tolerance is an unfixed non-determinism
 *       source, so fix the source instead of loosening the comparison.</li>
 *   <li><b>Volatile quantities</b> -- the explicitly enumerated whitelist (the whole
 *       provenance/runtime subtrees, artifact hashes, input and output absolute paths,
 *       wall-clock nanosecond fields). Every field outside the whitelist is compared as a core
 *       quantity; a new exemption requires a contract revision and must not be loosened ad hoc
 *       in the implementation.</li>
 *   <li><b>Identity information</b> -- {@code provenance.core.sourceTreeSha256} and
 *       {@code configuration.algorithmContract}; it takes no part in the verdict and is
 *       extracted separately for prominent display in the report.</li>
 * </ul>
 *
 * <p>This class neither decides the verdict nor writes it out; it only produces structured
 * divergence results. The verdict is settled by the stage 6 report executor in conjunction
 * with the input-location result.</p>
 */
public final class EvidenceCoreDiffer {

    /** Local wall-clock nanosecond metrics fields exempted here; the contract names each one, do not widen. */
    public static final Set<String> VOLATILE_METRIC_FIELDS = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList("totalSchedulingDecisionWallClockNanos", "totalPlanningDecisionWallClockNanos")));

    /**
     * Local elapsed-time keys exempted inside the event attributes (the REPRODUCIBILITY
     * contract declares them outside the fingerprint).
     */
    public static final Set<String> VOLATILE_EVENT_ATTRIBUTE_KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList("decisionElapsedNanos", "planningDecisionElapsedNanos")));

    private static final List<String> VOLATILE_SUBTREE_PREFIXES =
            Collections.unmodifiableList(Arrays.asList("/provenance", "/runtime"));

    /**
     * Exempt leaf patterns used by the comparison behavior.
     *
     * <p>Review-engineering F-001 fix (2026-10-19): the metrics/events wall-clock exemption
     * regexes are now **programmatically derived** from
     * {@link #VOLATILE_METRIC_FIELDS} / {@link #VOLATILE_EVENT_ATTRIBUTE_KEYS} -- previously
     * those two public constants had zero consumers repository-wide and the behavior was driven
     * only by the hard-coded regexes here, a dual-definition drift hazard (adding an item to a
     * constant did not change the behavior). With a single source of truth the constant is the
     * contract: any change to the field set is reflected in the comparison behavior
     * automatically. Behavior anchor = {@code EvidenceCoreDifferTest} (the existing contract
     * cases such as pointer exemptions and wall-clock attribute exemptions). The agreement
     * semantics are item-for-item identical to those before the fix.</p>
     */
    private static final List<Pattern> VOLATILE_POINTER_PATTERNS = buildVolatilePointerPatterns();

    private static List<Pattern> buildVolatilePointerPatterns() {
        List<Pattern> patterns = new ArrayList<Pattern>();
        // Identity declaration: changes legally as the code evolves, takes no part in the core
        // comparison, and is extracted separately for the report.
        patterns.add(Pattern.compile("/configuration/algorithmContract"));
        // Only path leaves are volatile: input identity/order and array length remain core.
        patterns.add(Pattern.compile("/configuration/workflowPaths/\\d+"));
        patterns.add(Pattern.compile("/inputs/\\d+/path"));
        patterns.add(Pattern.compile("/result/workflowOutcomes/\\d+/path"));
        patterns.add(Pattern.compile("/artifacts/\\d+/(sha256|sizeBytes)"));
        patterns.add(Pattern.compile("/metrics/(" + regexAlternation(VOLATILE_METRIC_FIELDS) + ")"));
        patterns.add(
                Pattern.compile("/events/\\d+/attributes/(" + regexAlternation(VOLATILE_EVENT_ATTRIBUTE_KEYS) + ")"));
        return Collections.unmodifiableList(patterns);
    }

    /** Literal-quoted alternation of the constant set into regex candidate branches (declaration order kept). */
    private static String regexAlternation(Set<String> keys) {
        StringBuilder alternation = new StringBuilder();
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                alternation.append('|');
            }
            alternation.append(Pattern.quote(key));
            first = false;
        }
        return alternation.toString();
    }

    /** Maximum recorded length of a serialized one-sided value; longer values are truncated. */
    private static final int MAX_VALUE_CHARS = 120;

    private static final Gson COMPACT = new GsonBuilder().serializeNulls().create();

    private EvidenceCoreDiffer() {}

    /**
     * Compare every core quantity of the original evidence with the newly produced rerun evidence.
     *
     * @param original evidence of the original run (manifest already parsed, sidecar files readable)
     * @param rerun evidence newly produced by the rerun
     * @return structured divergence result (core divergence list, volatile divergence records,
     *         identity information of both sides)
     * @throws IOException a sidecar file cannot be read
     */
    public static DiffResult compare(RerunEvidence original, RerunEvidence rerun) throws IOException {
        if (original == null || rerun == null) {
            throw new IllegalArgumentException("Original and rerun evidence are required");
        }
        List<Divergence> core = new ArrayList<Divergence>();
        List<Divergence> volatileNoted = new ArrayList<Divergence>();

        compareJson("", original.getManifest(), rerun.getManifest(), false, core, volatileNoted);
        compareJson(
                "", readJson(original.getMetricsPath()), readJson(rerun.getMetricsPath()), false, core, volatileNoted);
        compareEvents(original.getEventsPath(), rerun.getEventsPath(), core, volatileNoted);
        // Artifact hashes/sizes remain volatile, but the ledger contents are scientific core.
        // The separate namespace deliberately inherits NONE of the root provenance/runtime exemptions.
        if (original.getNetworkLedgerPath() != null || rerun.getNetworkLedgerPath() != null) {
            compareJson(
                    "/networkLedger",
                    original.getNetworkLedgerPath() == null ? null : readNetworkJson(original.getNetworkLedgerPath()),
                    rerun.getNetworkLedgerPath() == null ? null : readNetworkJson(rerun.getNetworkLedgerPath()),
                    false,
                    core,
                    volatileNoted);
        }

        if (original.getFileLifecyclePath() != null || rerun.getFileLifecyclePath() != null) {
            compareJson(
                    "/fileLifecycle",
                    original.getFileLifecyclePath() == null ? null : readNetworkJson(original.getFileLifecyclePath()),
                    rerun.getFileLifecyclePath() == null ? null : readNetworkJson(rerun.getFileLifecyclePath()),
                    false,
                    core,
                    volatileNoted);
        }

        if (original.getStorageLifecyclePath() != null || rerun.getStorageLifecyclePath() != null) {
            compareJson(
                    "/storageLifecycle",
                    original.getStorageLifecyclePath() == null
                            ? null
                            : readNetworkJson(original.getStorageLifecyclePath()),
                    rerun.getStorageLifecyclePath() == null ? null : readNetworkJson(rerun.getStorageLifecyclePath()),
                    false,
                    core,
                    volatileNoted);
        }

        // The metrics embedded in the manifest and the sidecar carry the same core quantities (the
        // artifact validator forces them equal), so comparing both would record each divergence
        // twice; deduplicate exactly on (pointer, old value, new value).
        return new DiffResult(
                deduplicate(core),
                volatileNoted,
                sourceTreeSha256(original.getManifest()),
                sourceTreeSha256(rerun.getManifest()),
                algorithmContract(original.getManifest()),
                algorithmContract(rerun.getManifest()));
    }

    private static List<Divergence> deduplicate(List<Divergence> divergences) {
        List<Divergence> unique = new ArrayList<Divergence>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Divergence divergence : divergences) {
            String key = divergence.getPointer() + "\u0000" + divergence.getOriginalValue() + "\u0000"
                    + divergence.getRerunValue();
            if (seen.add(key)) {
                unique.add(divergence);
            }
        }
        return unique;
    }

    // ---- generic recursive comparison of manifest and metrics ----

    private static void compareJson(
            String pointer,
            JsonElement original,
            JsonElement rerun,
            boolean volatileScope,
            List<Divergence> core,
            List<Divergence> volatileNoted) {
        if (volatileScope || isVolatileSubtreeRoot(pointer)) {
            // Volatile subtree: recurse to the leaves, record differences only, take no part in the
            // core comparison.
            compareVolatileScope(pointer, original, rerun, core, volatileNoted);
            return;
        }
        if (isVolatileLeaf(pointer)) {
            if (!nullSafeEquals(original, rerun)) {
                volatileNoted.add(new Divergence(pointer, describe(original), describe(rerun)));
            }
            return;
        }
        if (original == null || rerun == null || original.isJsonNull() || rerun.isJsonNull()) {
            // BAT-R3 (2026-10-27, third review round): JSON null is synonymous with "key absent" (both
            // mean undeclared) and joins explicit 0.0 in the absence test; any value outside
            // these three still goes through the literal comparison.
            JsonElement normalizedOriginal = original == null || original.isJsonNull() ? null : original;
            JsonElement normalizedRerun = rerun == null || rerun.isJsonNull() ? null : rerun;
            if (!nullSafeEquals(normalizedOriginal, normalizedRerun)) {
                // BAT-E4.1 (2026-10-27): a missing key/section on the pre-energy historical evidence
                // side is equivalent to the canonical zero value on the rerun side -- limited to the
                // energy-dimension pointers, and the present value must be the canonical zero; any
                // non-zero value still goes through core-quantity divergence.
                if (!isBackwardCompatibleEnergyAbsence(pointer, normalizedOriginal, normalizedRerun)) {
                    core.add(new Divergence(pointer, describe(original), describe(rerun)));
                }
            }
            return;
        }
        compareCoreValue(pointer, original, rerun, core, volatileNoted);
    }

    /**
     * 2026-10-26 complexity extraction from compareJson; statements verbatim; order unchanged.
     * Scope: handles only values already classified into a volatile subtree (object/array order
     * traversal records only, leaf differences go to volatileNoted, and nothing takes part in
     * the core comparison).
     */
    private static void compareVolatileScope(
            String pointer,
            JsonElement original,
            JsonElement rerun,
            List<Divergence> core,
            List<Divergence> volatileNoted) {
        if (original != null && rerun != null && original.isJsonObject() && rerun.isJsonObject()) {
            JsonObject a = original.getAsJsonObject();
            JsonObject b = rerun.getAsJsonObject();
            Set<String> keys = new TreeSet<String>();
            keys.addAll(a.keySet());
            keys.addAll(b.keySet());
            for (String key : keys) {
                compareJson(pointer + "/" + escapePointerToken(key), a.get(key), b.get(key), true, core, volatileNoted);
            }
            return;
        }
        if (original != null && rerun != null && original.isJsonArray() && rerun.isJsonArray()) {
            JsonArray a = original.getAsJsonArray();
            JsonArray b = rerun.getAsJsonArray();
            if (a.size() != b.size()) {
                volatileNoted.add(new Divergence(pointer, "array length " + a.size(), "array length " + b.size()));
            }
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                compareJson(pointer + "/" + i, a.get(i), b.get(i), true, core, volatileNoted);
            }
            return;
        }
        if (!nullSafeEquals(original, rerun)) {
            volatileNoted.add(new Divergence(pointer, describe(original), describe(rerun)));
        }
        return;
    }

    /**
     * 2026-10-26 complexity extraction from compareJson; statements verbatim; order unchanged.
     * Scope: handles only the core-quantity comparison of two non-null values on non-volatile
     * pointers (container recursion, or exact equality of leaves/scalars; divergences go to
     * core).
     */
    private static void compareCoreValue(
            String pointer,
            JsonElement original,
            JsonElement rerun,
            List<Divergence> core,
            List<Divergence> volatileNoted) {
        if (original.isJsonObject() && rerun.isJsonObject()) {
            JsonObject a = original.getAsJsonObject();
            JsonObject b = rerun.getAsJsonObject();
            Set<String> keys = new TreeSet<String>();
            keys.addAll(a.keySet());
            keys.addAll(b.keySet());
            for (String key : keys) {
                compareJson(
                        pointer + "/" + escapePointerToken(key), a.get(key), b.get(key), false, core, volatileNoted);
            }
            return;
        }
        if (original.isJsonArray() && rerun.isJsonArray()) {
            JsonArray a = original.getAsJsonArray();
            JsonArray b = rerun.getAsJsonArray();
            if (a.size() != b.size()) {
                core.add(new Divergence(pointer, "array length " + a.size(), "array length " + b.size()));
            }
            for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
                compareJson(pointer + "/" + i, a.get(i), b.get(i), false, core, volatileNoted);
            }
            return;
        }
        if (!nullSafeEquals(original, rerun)) {
            core.add(new Divergence(pointer, describe(original), describe(rerun)));
        }
    }

    /**
     * BAT-E4.1 (2026-10-27): backward-compatible absence equivalence for the energy dimension.
     *
     * <p>Semantics: the values count as equal only when one side is absent (the key does not
     * exist) and the other side is <b>canonical zero</b>. Applicable pointers:
     * {@code /energyMetrics} (the whole section recursively all zero, including the
     * vmEnergyJoules map) and {@code /platform/vms/N/(idlePowerWatts|maxPowerWatts)} (the number
     * is exactly 0.0). Non-zero values are never exempt -- a rerun drift on evidence that
     * carries power is still compared in full (the conservation-law defense is not relaxed).
     * This is not a widening of the volatile whitelist: the numbers remain core quantities, and
     * "historical evidence before E4 has no such key" has the same meaning as "a legally
     * all-zero section after E4". Contract basis: see
     * docs/experiments/RERUN_DIFF_CONTRACT.md (BAT-E4.1 paragraph).</p>
     */
    private static boolean isBackwardCompatibleEnergyAbsence(String pointer, JsonElement original, JsonElement rerun) {
        if (original != null && rerun != null) {
            return false;
        }
        JsonElement present = original != null ? original : rerun;
        if ("/energyMetrics".equals(pointer)) {
            return present != null && present.isJsonObject() && jsonTreeIsAllZeroNumbers(present);
        }
        if (ENERGY_POWER_LEAF_POINTER.matcher(pointer).matches()) {
            return present != null
                    && present.isJsonPrimitive()
                    && present.getAsJsonPrimitive().isNumber()
                    && present.getAsDouble() == 0.0;
        }
        return false;
    }

    /** Recursive check: every numeric leaf of the object/array tree is exactly 0.0 and no leaf has another type. */
    private static boolean jsonTreeIsAllZeroNumbers(JsonElement element) {
        if (element.isJsonObject()) {
            for (java.util.Map.Entry<String, JsonElement> entry :
                    element.getAsJsonObject().entrySet()) {
                if (!jsonTreeIsAllZeroNumbers(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (!jsonTreeIsAllZeroNumbers(child)) {
                    return false;
                }
            }
            return true;
        }
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber() && element.getAsDouble() == 0.0;
    }

    /** Energy power leaf pointer: /platform/vms/&lt;N&gt;/idlePowerWatts or maxPowerWatts. */
    private static final java.util.regex.Pattern ENERGY_POWER_LEAF_POINTER =
            java.util.regex.Pattern.compile("^/platform/vms/\\d+/(idlePowerWatts|maxPowerWatts)$");

    // ---- events compared line by line (the line count is the total event count) ----

    private static void compareEvents(
            Path originalEvents, Path rerunEvents, List<Divergence> core, List<Divergence> volatileNoted)
            throws IOException {
        List<String> originalLines = Files.readAllLines(originalEvents, StandardCharsets.UTF_8);
        List<String> rerunLines = Files.readAllLines(rerunEvents, StandardCharsets.UTF_8);
        if (originalLines.size() != rerunLines.size()) {
            core.add(new Divergence(
                    "/events/eventCount", String.valueOf(originalLines.size()), String.valueOf(rerunLines.size())));
        }
        for (int i = 0; i < Math.min(originalLines.size(), rerunLines.size()); i++) {
            JsonElement a = JsonParser.parseString(originalLines.get(i));
            JsonElement b = JsonParser.parseString(rerunLines.get(i));
            compareJson("/events/" + i, a, b, false, core, volatileNoted);
        }
    }

    /** Encode a member token before path classification; literal separators are not structure. */
    private static String escapePointerToken(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    // ---- exemption whitelist tests (contract section on volatile quantities, explicit enumeration) ----

    /** Whole-subtree exemption (provenance/runtime); recursion to the leaves inside it only records. */
    private static boolean isVolatileSubtreeRoot(String pointer) {
        for (String prefix : VOLATILE_SUBTREE_PREFIXES) {
            if (pointer.equals(prefix) || pointer.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Exactly enumerated leaf exemptions (paths, hashes, wall-clock nanoseconds, identity declaration). */
    private static boolean isVolatileLeaf(String pointer) {
        for (Pattern pattern : VOLATILE_POINTER_PATTERNS) {
            if (pattern.matcher(pointer).matches()) {
                return true;
            }
        }
        return false;
    }

    // ---- identity information extraction (no part in the verdict; for prominent report display) ----

    /** Extract {@code provenance.core.sourceTreeSha256} (null when missing) for report display. */
    public static String sourceTreeSha256(JsonObject manifest) {
        JsonElement value = manifest;
        for (String key : Arrays.asList("provenance", "core", "sourceTreeSha256")) {
            if (!(value instanceof JsonObject) || !((JsonObject) value).has(key)) {
                return null;
            }
            value = ((JsonObject) value).get(key);
        }
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    /** Extract the compact serialized text of {@code configuration.algorithmContract} (null when missing). */
    public static String algorithmContract(JsonObject manifest) {
        JsonObject configuration = manifest.getAsJsonObject("configuration");
        if (configuration == null || !configuration.has("algorithmContract")) {
            return null;
        }
        JsonElement value = configuration.get("algorithmContract");
        return value.isJsonNull() ? null : COMPACT.toJson(value);
    }

    // ---- small helpers ----

    private static JsonObject readJson(Path path) throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static JsonObject readNetworkJson(Path path) throws IOException {
        String text = StandardCharsets.UTF_8
                .newDecoder()
                .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(path)))
                .toString();
        try {
            return org.workflowsim.data.NetworkLedgerCodec.parseDocument(text);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid network JSON during core comparison", invalid);
        }
    }

    private static boolean nullSafeEquals(JsonElement a, JsonElement b) {
        return ExactJsonValues.equal(a, b);
    }

    /**
     * Serialized value description: null is explicitly marked missing; over-long values are
     * truncated and their total length is recorded.
     */
    private static String describe(JsonElement value) {
        if (value == null) {
            return "<missing>";
        }
        String text = COMPACT.toJson(value);
        if (text.length() <= MAX_VALUE_CHARS) {
            return text;
        }
        return text.substring(0, MAX_VALUE_CHARS) + "…[truncated:" + text.length() + "]";
    }

    /** One divergence: JSON pointer plus the values of both sides (already truncated). */
    public static final class Divergence {

        private final String pointer;
        private final String originalValue;
        private final String rerunValue;

        Divergence(String pointer, String originalValue, String rerunValue) {
            this.pointer = pointer;
            this.originalValue = originalValue;
            this.rerunValue = rerunValue;
        }

        /** JSON pointer of the divergent field (for example {@code /result/makespanSeconds}). */
        public String getPointer() {
            return pointer;
        }

        public String getOriginalValue() {
            return originalValue;
        }

        public String getRerunValue() {
            return rerunValue;
        }

        @Override
        public String toString() {
            return pointer + ": " + originalValue + " -> " + rerunValue;
        }
    }

    /**
     * Comparison result: core-quantity divergences, volatile divergence records, and the
     * identity information of both sides.
     */
    public static final class DiffResult {

        private final List<Divergence> coreDivergences;
        private final List<Divergence> volatileNoted;
        private final String originalSourceTreeSha256;
        private final String rerunSourceTreeSha256;
        private final String originalAlgorithmContract;
        private final String rerunAlgorithmContract;

        DiffResult(
                List<Divergence> coreDivergences,
                List<Divergence> volatileNoted,
                String originalSourceTreeSha256,
                String rerunSourceTreeSha256,
                String originalAlgorithmContract,
                String rerunAlgorithmContract) {
            this.coreDivergences = Collections.unmodifiableList(new ArrayList<Divergence>(coreDivergences));
            this.volatileNoted = Collections.unmodifiableList(new ArrayList<Divergence>(volatileNoted));
            this.originalSourceTreeSha256 = originalSourceTreeSha256;
            this.rerunSourceTreeSha256 = rerunSourceTreeSha256;
            this.originalAlgorithmContract = originalAlgorithmContract;
            this.rerunAlgorithmContract = rerunAlgorithmContract;
        }

        /** Core-quantity divergence list (empty = every value bitwise identical). */
        public List<Divergence> getCoreDivergences() {
            return coreDivergences;
        }

        /** Whether every core quantity is bitwise identical. */
        public boolean isIdenticalCore() {
            return coreDivergences.isEmpty();
        }

        /** Records of volatile fields whose whitelisted values changed (recorded only, no part in the verdict). */
        public List<Divergence> getVolatileNoted() {
            return volatileNoted;
        }

        public String getOriginalSourceTreeSha256() {
            return originalSourceTreeSha256;
        }

        public String getRerunSourceTreeSha256() {
            return rerunSourceTreeSha256;
        }

        /** Whether the code-tree hashes match (the rerun-side hash is the hash of the current code). */
        public boolean isCodeIdentityMatching() {
            return originalSourceTreeSha256 != null && originalSourceTreeSha256.equals(rerunSourceTreeSha256);
        }

        public String getOriginalAlgorithmContract() {
            return originalAlgorithmContract;
        }

        public String getRerunAlgorithmContract() {
            return rerunAlgorithmContract;
        }
    }
}
