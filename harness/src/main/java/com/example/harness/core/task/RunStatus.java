package com.example.harness.core.task;

/** P4 TaskRun 状态；终态不可再次迁移。 */
public enum RunStatus {
    CREATED, RUNNING, WAITING_APPROVAL,
    SUCCESS, PARTIAL, FAILED, STOPPED, INTERRUPTED;

    public boolean terminal() {
        return this == SUCCESS || this == PARTIAL || this == FAILED
                || this == STOPPED || this == INTERRUPTED;
    }
}
