package com.example.harness.model;

import java.util.List;

/** 项目 Git 状态总览（悬浮面板数据源）。repo=false 时 changes 为空并附原因。 */
public record GitStatus(boolean repo, String branch, int ahead, int behind,
                        List<Change> changes, int totalAdditions, int totalDeletions,
                        String error) {

    /** 单文件变更：code 为 porcelain 状态位（M/A/D/R/?? 等）。 */
    public record Change(String path, String code, String label,
                         int additions, int deletions) {
    }

    public static GitStatus notRepo(String reason) {
        return new GitStatus(false, null, 0, 0, List.of(), 0, 0, reason);
    }
}
