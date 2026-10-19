---
name: code-review
description: 快速审查项目代码：浏览目录结构、读取目标文件并按检查清单（命名/边界条件/空指针/资源泄漏/安全隐患）给出审查意见
triggers:
  - "代码审查"
  - "review一下"
  - "检查代码"
  - "帮我看看这段代码"
when_to_use: 用户想让人帮忙看代码质量、找 bug 或安全隐患时
enabled: true
version: 1
---

社区最热门的工程效率类技能之一（如 code-reviewer）。
参数说明：review_dir=要审查的目录（默认 . 即项目根），review_target=重点审查的文件路径。
执行后从工具输出中查看目录与代码内容，再结合下面的检查清单人工/AI 补充结论：
- 命名与可读性：变量/方法名是否表达意图
- 边界条件：空集合、null、越界、超长输入
- 资源管理：流/连接是否关闭、异常是否吞掉
- 安全：路径拼接、SQL 注入、敏感信息打印
- 重复代码与可简化点

1. listFiles(dir="{{review_dir}}")
2. fileRead(path="{{review_target}}")
