package com.example.harness.core.task;

/** 执行器返回给统一 Finalizer 的最小结果；事实细节从事件流与指标构建。 */
public record ExecutionResult(RunStatus status, String conclusion, Long traceId,
                              String mode, String failureCode, String failureSummary,
                              boolean stepLimitReached) {

    public static ExecutionResult success(String conclusion, Long traceId, String mode) {
        return new ExecutionResult(RunStatus.SUCCESS, conclusion, traceId, mode, null, null, false);
    }

    public static ExecutionResult stopped(String conclusion, Long traceId, String mode) {
        return new ExecutionResult(RunStatus.STOPPED, conclusion, traceId, mode, null, null, false);
    }

    public static ExecutionResult failed(String conclusion, Long traceId, String mode,
                                         String code, String summary) {
        return new ExecutionResult(RunStatus.FAILED, conclusion, traceId, mode, code, summary, false);
    }

    public ExecutionResult withMode(String newMode) {
        return new ExecutionResult(status, conclusion, traceId, newMode,
                failureCode, failureSummary, stepLimitReached);
    }
}
