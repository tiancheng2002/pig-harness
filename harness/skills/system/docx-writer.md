---
name: docx-writer
description: 把主题内容整理成结构清晰的 Word 文档（.docx），自动生成概述、要点列表与总结的章节骨架
triggers:
  - "生成word"
  - "生成文档"
  - "写个报告"
  - "导出docx"
when_to_use: 用户要求生成 Word 文档/报告，且内容可以按章节组织时
enabled: true
version: 1
---

参考 Anthropic 官方 docx 技能（文档处理类中最常用）。
参数说明：file_name=输出文件名（如 report.docx），doc_title=文档主标题，doc_overview=概述段落，point_1/2/3=主要内容要点，doc_summary=总结段落。
生成后可在对话里继续让 Agent 修改（"把第二章展开写"）。

1. docxWrite(path="{{file_name}}", title="{{doc_title}}", content="# {{doc_title}}\n\n## 概述\n{{doc_overview}}\n\n## 主要内容\n- {{point_1}}\n- {{point_2}}\n- {{point_3}}\n\n## 总结\n{{doc_summary}}")
