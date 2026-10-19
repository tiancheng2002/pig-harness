---
name: create_login_page
description: 创建带用户名、密码输入框和提交按钮的 HTML 登录页面
triggers:
  - "创建登录页"
  - "做一个登录页面"
  - "生成登录页"
  - "帮我写个登录页"
when_to_use: 用户明确要求创建登录页面时
enabled: true
version: 1
---
1. fileWrite(path="{{page}}.html", content="<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"UTF-8\">\n<title>登录</title>\n<style>\nbody{min-height:100vh;display:flex;align-items:center;justify-content:center;background:#f0f2f5;font-family:system-ui,sans-serif}\n.card{background:#fff;padding:32px 40px;border-radius:12px;box-shadow:0 8px 24px rgba(0,0,0,.1);width:320px}\nh1{margin:0 0 24px;font-size:20px;text-align:center}\n.field{margin-bottom:16px}\nlabel{display:block;font-size:13px;color:#666;margin-bottom:6px}\ninput{width:100%;padding:10px;border:1px solid #d9d9d9;border-radius:6px}\nbutton{width:100%;padding:11px;background:#1677ff;color:#fff;border:none;border-radius:6px;cursor:pointer}\n</style>\n</head>\n<body>\n<form class=\"card\" onsubmit=\"return false\">\n<h1>用户登录</h1>\n<div class=\"field\"><label>用户名</label><input type=\"text\" placeholder=\"请输入用户名\"></div>\n<div class=\"field\"><label>密码</label><input type=\"password\" placeholder=\"请输入密码\"></div>\n<button>登录</button>\n</form>\n</body>\n</html>")
