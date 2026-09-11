import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { viteSingleFile } from 'vite-plugin-singlefile'

// 产物必须是单个自包含 HTML：JCEF 直接用 loadHTML() 加载，
// 不需要运行时提取资源、不需要自定义 CefResourceHandler。
export default defineConfig({
  plugins: [react(), viteSingleFile()],
  build: {
    outDir: 'dist',
    cssCodeSplit: false,
    assetsInlineLimit: 100_000_000, // 一切资源内联
    rollupOptions: {
      output: { inlineDynamicImports: true },
    },
  },
})
