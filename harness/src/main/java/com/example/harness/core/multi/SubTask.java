package com.example.harness.core.multi;

import java.util.List;

/**
 * 拆分产物（M4）：Planner.decompose 输出的单个子任务。
 * 数据依赖与输出路径全部在计划期确定，运行期只执行、不协商。
 */
public record SubTask(String id,
                      String description,
                      List<String> dependsOn,
                      List<String> outputPaths,
                      List<String> suggestedTools) {

    public SubTask {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        outputPaths = outputPaths == null ? List.of() : List.copyOf(outputPaths);
        suggestedTools = suggestedTools == null ? List.of() : List.copyOf(suggestedTools);
    }
}
