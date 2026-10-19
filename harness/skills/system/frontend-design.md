---
name: frontend-design
description: 生成有设计感的现代前端页面（登录页/落地页），深色玻璃拟态风格、渐变主按钮、响应式布局，避免平庸的 AI 模板感
triggers:
  - "设计一个页面"
  - "做个登录页"
  - "落地页"
  - "landing page"
  - "页面设计"
when_to_use: 用户需要创建视觉质量较高的 HTML 页面（登录页、落地页、产品页）时
enabled: true
version: 1
---

灵感来自 Anthropic 官方 frontend-design 技能（社区最火的技能之一）。
参数说明：file_name=输出文件名，page_title=页面主标题，page_subtitle=副标题，button_text=按钮文字。
页面采用深色渐变背景 + 卡片式布局，自带响应式与悬停交互，可直接在浏览器打开预览。

1. fileWrite(path="{{file_name}}", content="<!DOCTYPE html>\n<html lang='zh'>\n<head>\n<meta charset='utf-8'>\n<meta name='viewport' content='width=device-width,initial-scale=1'>\n<title>{{page_title}}</title>\n<style>\n:root{--bg:#0b1020;--card:#141b36;--accent:#5b8cff;--text:#e8ecf8;--muted:#8b94b3}\n*{box-sizing:border-box;margin:0}\nbody{font-family:'Segoe UI',system-ui,sans-serif;background:radial-gradient(1100px 560px at 82% -12%,#1e2c61 0%,var(--bg) 58%);color:var(--text);min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}\n.card{width:100%;max-width:400px;background:var(--card);border:1px solid #232c4d;border-radius:16px;padding:36px 32px;box-shadow:0 24px 60px rgba(0,0,0,.45)}\nh1{font-size:22px;margin-bottom:6px;letter-spacing:.5px}\n.sub{color:var(--muted);font-size:13.5px;margin-bottom:24px}\nlabel{display:block;font-size:12.5px;color:var(--muted);margin:14px 0 6px}\ninput{width:100%;padding:11px 13px;border-radius:10px;border:1px solid #2a3560;background:#0e142b;color:var(--text);font-size:14px;outline:none;transition:border .15s}\ninput:focus{border-color:var(--accent)}\n.btn{width:100%;margin-top:24px;padding:12px;border:none;border-radius:10px;background:linear-gradient(135deg,#5b8cff,#7c5bff);color:#fff;font-size:14.5px;font-weight:600;cursor:pointer;transition:opacity .15s}\n.btn:hover{opacity:.88}\n.foot{margin-top:18px;text-align:center;color:var(--muted);font-size:12px}\n@media(max-width:480px){.card{padding:28px 22px}}\n</style>\n</head>\n<body>\n<div class='card'>\n<h1>{{page_title}}</h1>\n<p class='sub'>{{page_subtitle}}</p>\n<form onsubmit='event.preventDefault()'>\n<label>用户名</label>\n<input placeholder='请输入用户名' autocomplete='username' />\n<label>密码</label>\n<input type='password' placeholder='请输入密码' autocomplete='current-password' />\n<button class='btn' type='submit'>{{button_text}}</button>\n</form>\n<p class='foot'>由 Harness 驱动</p>\n</div>\n</body>\n</html>")
