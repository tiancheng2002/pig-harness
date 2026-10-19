---
name: weekly-report
description: 生成结构化的 Word 周报（.docx），含本周进展、风险与问题、下周计划三个固定章节
triggers:
  - "周报"
  - "本周总结"
  - "weekly report"
  - "生成周报"
when_to_use: 用户需要按周汇报工作进展、输出正式周报文档时
enabled: true
version: 1
---

职场高频场景，也是本 Harness 演示最充分的技能。
参数说明：file_name=输出文件名（默认 weekly-report.docx），report_title=报告标题，progress_1/2=本周进展要点，risk_1=风险或问题，plan_1=下周计划要点。
缺省参数会被自动补齐为合理默认值，生成后可继续对话微调内容。

1. docxWrite(path="{{file_name}}", title="{{report_title}}", content="# {{report_title}}\n\n## 本周进展\n- {{progress_1}}\n- {{progress_2}}\n\n## 风险与问题\n- {{risk_1}}\n\n## 下周计划\n- {{plan_1}}")
