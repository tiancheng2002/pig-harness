import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080'
    }
  },
  build: {
    // 输出到后端静态资源目录；不清空目录（safe-delete 拦截会中断构建），直接覆盖写入
    outDir: '../harness/src/main/resources/static',
    emptyOutDir: false
  }
})
