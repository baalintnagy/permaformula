import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, process.cwd(), 'PERMAFORMULA_');
    const apiOrigin =
        process.env.PERMAFORMULA_DEV_API || env.PERMAFORMULA_DEV_API || 'http://localhost:8086';
    const uiOrigin = process.env.PERMAFORMULA_DEV_UI_ORIGIN || env.PERMAFORMULA_DEV_UI_ORIGIN;
    const uiUrl = uiOrigin ? new URL(uiOrigin) : undefined;
    return {
        define: {
            'import.meta.env.VITE_PERMAFORMULA_API_ORIGIN': JSON.stringify(
                mode === 'development' ? apiOrigin : '',
            ),
        },
        plugins: [
            react(),
            {
                name: 'permaformula-social-metadata',
                apply: 'serve',
                transformIndexHtml(html) {
                    const origin = uiUrl?.origin || 'http://127.0.0.1:5173';
                    const escape = (value: string) =>
                        value
                            .replace(/&/g, '&amp;')
                            .replace(/"/g, '&quot;')
                            .replace(/</g, '&lt;')
                            .replace(/>/g, '&gt;');
                    const url = escape(`${origin}/`);
                    const image = escape(`${origin}/assets/og.jpg`);
                    return html.replace(
                        '<!-- PERMAFORMULA_PUBLIC_METADATA -->',
                        `<link rel="canonical" href="${url}" />\n<meta property="og:url" content="${url}" />\n<meta property="og:image" content="${image}" />\n<meta name="twitter:image" content="${image}" />`,
                    );
                },
            },
        ],
        build: {
            rollupOptions: {
                input: { main: 'index.html', admin: 'admin.html' },
                output: {
                    manualChunks(id) {
                        if (
                            /node_modules\/(?:@codemirror\/lang-(?:html|css|javascript)|@lezer\/(?:html|css|javascript))\//.test(
                                id,
                            )
                        ) {
                            return 'languages';
                        }
                        if (/node_modules\/(?:codemirror|@codemirror|@lezer)\//.test(id)) {
                            return 'editors';
                        }
                        if (/node_modules\/(?:react|react-dom|scheduler)\//.test(id)) {
                            return 'react';
                        }
                    },
                },
            },
        },
        server: {
            host: '127.0.0.1',
            port: 5173,
            strictPort: true,
            allowedHosts: uiUrl ? [uiUrl.hostname] : [],
            hmr: uiUrl
                ? {
                      protocol: uiUrl.protocol === 'https:' ? 'wss' : 'ws',
                      host: uiUrl.hostname,
                      clientPort: Number(uiUrl.port) || (uiUrl.protocol === 'https:' ? 443 : 80),
                  }
                : undefined,
            proxy: Object.fromEntries(
                [
                    '/library-items',
                    '/admin/cache-item',
                    '/admin/login',
                    '/library-item',
                    '/ubcq',
                    '/exp-svg',
                    '/exp-jpg',
                    '/html-svg',
                    '/html-jpg',
                    '/swagger-ui',
                    '/v3/api-docs',
                ].map((path) => [
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
