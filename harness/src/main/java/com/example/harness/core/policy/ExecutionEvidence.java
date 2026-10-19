package com.example.harness.core.policy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Server-observed evidence used by exploration and completion guards. */
public final class ExecutionEvidence {
    public enum VerificationKind { REPRO, TEST, BUILD, LINT }
    public record Verification(VerificationKind kind, int sequence, boolean ok) {}

    private static final Pattern TEST_FILE = Pattern.compile(
            "(^|/)(src/test|tests?|__tests__|spec)(/|$)|(?:test|spec)\\.[a-z0-9]+$",
            Pattern.CASE_INSENSITIVE);
    /** Throwaway helpers a run creates for itself: they must not invalidate green evidence. */
    private static final Pattern SCRATCH_FILE = Pattern.compile(
            "(^|/)(tmp|temp|\\.harness)(/|$)|\\.(log|tmp)$", Pattern.CASE_INSENSITIVE);

    private int sequence;
    private int discoveryCalls;
    private int successfulDiscoveryCalls;
    private int mutations;
    private boolean testFileChanged;
    private int consecutiveFailures;
    private int totalFailures;
    private int questions;
    private boolean reproductionObserved;
    /** Sequences of durable (production or test) mutations, ascending; drives lastMutation(). */
    private final List<Integer> durableMutations = new ArrayList<>();
    /** Sequences of production-code mutations, ascending; drives the reproduction ordering. */
    private final List<Integer> productionMutations = new ArrayList<>();
    /** Relative path -> sequence, for files this run created and may delete again. */
    private final Map<String, Integer> createdPaths = new HashMap<>();
    /** Latest result per kind (diagnostics) and latest successful result per kind (gating). */
    private final Map<VerificationKind, Verification> lastResult =
            new EnumMap<>(VerificationKind.class);
    private final Map<VerificationKind, Verification> lastSuccess =
            new EnumMap<>(VerificationKind.class);
    private final Map<String, Integer> failureSignatures = new HashMap<>();

    public int nextSequence() { return ++sequence; }

    public void recordDiscovery(boolean ok) {
        discoveryCalls++;
        if (ok) successfulDiscoveryCalls++;
        recordResult(ok);
    }

    public void recordMutation(String path, boolean ok) {
        recordMutation("fileWrite", path, ok);
    }

    /**
     * Records a file mutation. Deleting a file this same run created is treated as self-cleanup:
     * both the creation and the deletion are rolled back out of the ledger, so a scratch script
     * written between a patch and its verification cannot invalidate an already green test run.
     */
    public void recordMutation(String tool, String path, boolean ok) {
        recordResult(ok);
        if (!ok) return;
        String key = normalize(path);
        if ("fileDelete".equals(tool) && createdPaths.containsKey(key)) {
            int created = createdPaths.remove(key);
            if (durableMutations.remove(Integer.valueOf(created))) {
                mutations = Math.max(0, mutations - 1);
                productionMutations.remove(Integer.valueOf(created));
            }
            return;
        }
        if (isScratchPath(key)) return;
        mutations++;
        boolean test = isTestPath(key);
        testFileChanged |= test;
        durableMutations.add(sequence);
        if (!test) productionMutations.add(sequence);
        // Only a create can later be undone by a delete; an edit of a pre-existing file cannot.
        if ("fileWrite".equals(tool) || "docxWrite".equals(tool)) {
            createdPaths.putIfAbsent(key, sequence);
        }
    }

    public void recordOther(boolean ok) { recordResult(ok); }

    public void recordVerification(VerificationKind kind, boolean ok) {
        if ((kind == VerificationKind.REPRO || (kind == VerificationKind.TEST && !ok))
                && sequence < firstProductionMutation()) reproductionObserved = true;
        Verification value = new Verification(kind, sequence, ok);
        lastResult.put(kind, value);
        // A later noisy invocation (a pipeline whose exit code came from grep, say) must not
        // erase an observation that genuinely passed.
        if (ok) lastSuccess.put(kind, value);
    }

    /** One shell invocation may satisfy multiple verification kinds, but counts as one tool result. */
    public void finishVerificationCall(boolean ok) { recordResult(ok); }

    private void recordResult(boolean ok) {
        if (ok) consecutiveFailures = 0;
        else {
            consecutiveFailures++;
            totalFailures++;
        }
    }

    public void recordQuestion() { questions++; }
    public int recordFailureSignature(String signature) {
        if (signature == null || signature.isBlank()) return 1;
        return failureSignatures.merge(signature, 1, Integer::sum);
    }
    public int totalCalls() { return sequence; }
    public int discoveryCalls() { return discoveryCalls; }
    public int successfulDiscoveryCalls() { return successfulDiscoveryCalls; }
    public int mutations() { return mutations; }
    public int lastMutation() {
        return durableMutations.isEmpty() ? 0 : durableMutations.get(durableMutations.size() - 1);
    }
    public boolean testFileChanged() { return testFileChanged; }

    private int firstProductionMutation() {
        return productionMutations.isEmpty() ? Integer.MAX_VALUE : productionMutations.get(0);
    }
    public int consecutiveFailures() { return consecutiveFailures; }
    public int totalFailures() { return totalFailures; }
    public int questions() { return questions; }

    public boolean hasSuccessfulAfter(VerificationKind kind, int afterSequence) {
        Verification value = lastSuccess.get(kind);
        return value != null && value.sequence() > afterSequence;
    }

    public Verification latestResult(VerificationKind kind) { return lastResult.get(kind); }

    /** Kinds that have passed at least once; used to detect a completion retry that added nothing. */
    public Set<VerificationKind> successfulKinds() {
        return lastSuccess.isEmpty() ? EnumSet.noneOf(VerificationKind.class)
                : EnumSet.copyOf(lastSuccess.keySet());
    }

    public boolean hasReproductionBeforeProductionMutation() {
        return reproductionObserved;
    }

    public static boolean isDiscoveryTool(String name) {
        return List.of("fileRead", "listFiles", "grep", "codeSearch").contains(name);
    }

    public static boolean isMutationTool(String name) {
        return List.of("fileWrite", "fileEdit", "fileDelete", "docxWrite").contains(name);
    }

    public static boolean isTestPath(String path) {
        if (path == null) return false;
        return TEST_FILE.matcher(normalize(path)).find();
    }

    public static boolean isScratchPath(String path) {
        if (path == null) return false;
        return SCRATCH_FILE.matcher(normalize(path)).find();
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
