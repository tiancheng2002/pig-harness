package com.example.harness.core.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionBudgetTest {

    private static ExecutionBudget budget(String prompt) {
        return new ExecutionBudget(10, 200, 1800, 5, TaskIntent.infer(prompt));
    }

    @Test void scopeScalesTheAdvisoryNudge() {
        assertEquals(10, budget("请修复分页边界的缺陷").softCalls());
        assertEquals(20, budget("给任务列表加一个导出按钮").softCalls());
        assertEquals(30, budget("新增任务标签功能，从数据库到前端交互的全栈实现").softCalls());
    }

    @Test void volumeAloneNeverStopsAProductiveRun() {
        ExecutionBudget b = budget("新增任务标签功能，从数据库到前端交互的全栈实现");
        assertEquals(ExecutionBudget.State.SOFT, b.evaluate(30, 0, 60).state());
        for (int calls = 31; calls <= 150; calls++) {
            assertEquals(ExecutionBudget.State.OK, b.evaluate(calls, 0, calls * 10L).state(),
                    "有进展时不应被调用数或时间切断，calls=" + calls);
        }
    }

    @Test void anUnproductiveStreakStopsTheRun() {
        ExecutionBudget b = budget("请修复分页边界的缺陷");
        assertEquals(ExecutionBudget.State.OK, b.evaluate(6, 4, 30).state());
        assertTrue(b.evaluate(7, 5, 30).hard());
    }

    @Test void theAbsoluteCeilingIsASafetyNetNotARoutineLimit() {
        ExecutionBudget b = budget("请修复分页边界的缺陷");
        b.evaluate(10, 0, 10); // consume the soft notice
        assertEquals(ExecutionBudget.State.OK, b.evaluate(199, 0, 10).state());
        assertTrue(b.evaluate(200, 0, 10).hard());
    }

    @Test void timeoutOnlyBitesWhenProgressHasAlsoStalled() {
        ExecutionBudget stillWorking = budget("请修复分页边界的缺陷");
        assertEquals(ExecutionBudget.State.OK, stillWorking.evaluate(9, 0, 9999).state());

        ExecutionBudget stalled = budget("请修复分页边界的缺陷");
        stalled.evaluate(10, 0, 10); // consume the soft notice
        assertTrue(stalled.evaluate(11, 3, 9999).hard());
    }

    @Test void eachVerdictIsAnnouncedOnlyOnce() {
        ExecutionBudget b = budget("请修复分页边界的缺陷");
        assertTrue(b.evaluate(10, 0, 10).soft());
        assertEquals(ExecutionBudget.State.OK, b.evaluate(11, 0, 10).state());
        assertTrue(b.evaluate(12, 5, 10).hard());
        assertEquals(ExecutionBudget.State.OK, b.evaluate(13, 9, 10).state());
    }
}
