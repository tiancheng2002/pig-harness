package com.example.harness.core.policy;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import static com.example.harness.core.policy.ExecutionEvidence.VerificationKind;

/** Recognizes common non-interactive validation commands without pretending arbitrary shell is verification. */
public final class VerificationClassifier {
    private VerificationClassifier() {}

    public static Set<VerificationKind> classify(String command) {
        String c = command == null ? "" : command.toLowerCase(Locale.ROOT);
        EnumSet<VerificationKind> out = EnumSet.noneOf(VerificationKind.class);
        boolean skipsTests = c.contains("skiptests") || c.contains("skip_tests") || c.contains("--skip-tests");
        if (c.matches("(?s).*(mvn(?:w|\\.cmd)?\\s+[^;&|]*\\btest\\b|gradle(?:w|\\.bat)?\\s+[^;&|]*\\btest\\b|"
                + "npm\\s+(?:run\\s+)?test|pnpm\\s+(?:run\\s+)?test|yarn\\s+test|pytest|python\\s+-m\\s+pytest|"
                + "go\\s+test|cargo\\s+test).*")) out.add(VerificationKind.TEST);
        // Compiled languages cannot run their tests without compiling first: a green test run
        // is also build evidence. Demanding a separate compile afterwards only burns calls.
        if (out.contains(VerificationKind.TEST)
                && (c.contains("mvn") || c.contains("gradle") || c.contains("go test") || c.contains("cargo test"))) {
            out.add(VerificationKind.BUILD);
        }
        if (c.matches("(?s).*(mvn(?:w|\\.cmd)?\\s+[^;&|]*\\b(package|verify|compile)\\b|"
                + "gradle(?:w|\\.bat)?\\s+[^;&|]*\\b(build|assemble)\\b|npm\\s+run\\s+build|"
                + "pnpm\\s+(?:run\\s+)?build|yarn\\s+build|go\\s+build|cargo\\s+build).*")) {
            out.add(VerificationKind.BUILD);
            if (!skipsTests && (c.contains("mvn") && (c.contains("package") || c.contains("verify")))) {
                out.add(VerificationKind.TEST);
            }
        }
        if (c.matches("(?s).*(npm|pnpm|yarn).{0,12}lint.*") || c.contains("checkstyle")
                || c.contains("spotless:check")) out.add(VerificationKind.LINT);
        if (c.contains("repro") || c.contains("probe") || c.contains("复现")) out.add(VerificationKind.REPRO);
        return out;
    }
}
