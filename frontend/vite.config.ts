import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'PERMMATH_');
  const apiOrigin = process.env.PERMMATH_DEV_API || env.PERMMATH_DEV_API || 'http://localhost:8086';
  return {
    define: {
      'import.meta.env.VITE_PERMMATH_API_ORIGIN': JSON.stringify(
        mode === 'development' ? apiOrigin : '',
      ),
    },
    plugins: [react()],
    build: {
      rollupOptions: {
        output: {
          manualChunks: {
            editors: ['codemirror', '@codemirror/language', '@lezer/highlight'],
            languages: ['@codemirror/lang-html', '@codemirror/lang-css'],
            react: ['react', 'react-dom/client'],
          },
        },
      },
    },
    server: {
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      proxy: Object.fromEntries(
        ['/url', '/img', '/image', '/swagger-ui', '/v3/api-docs'].map((path) => [
          path,
          {
            target: apiOrigin,
            changeOrigin: true,
          },
        ]),
      ),
    },
  };
});
