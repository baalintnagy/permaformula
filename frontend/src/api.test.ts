// @vitest-environment jsdom
import { describe, expect, it, vi, afterEach } from 'vitest';
import {
    createPermalink,
    decodeUbcq,
    extractUbcq,
    formulaUrl,
    imageRequestUrl,
    jpegUrl,
    permalinkRequest,
    validStyleRef,
} from './api';
import { previewDocument } from './preview-document';

afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
});

describe('UBCQ decoding', () => {
    it('extracts raw and percent-encoded URL values without losing plus signs', () => {
        const value = 'H4sI+a/b+/==';
        expect(extractUbcq(`  ${value}  `)).toBe(value);
        expect(
            extractUbcq(
                `https://example.com/html-svg?ubcq=${encodeURIComponent(value)}&other=1#preview`,
            ),
        ).toBe(value);
        expect(extractUbcq(`/html-jpg?other=1&ubcq=${value}#preview`)).toBe(value);
        expect(() => extractUbcq('https://example.com/html-svg?formula=x')).toThrow('Paste a UBCQ');
    });

    it('loads original sources and resets absent optional settings', async () => {
        const fetchMock = vi
            .fn()
            .mockResolvedValue(new Response(JSON.stringify({ forumla: '<p>α</p>' })));
        vi.stubGlobal('fetch', fetchMock);
        expect(await decodeUbcq('H4sI+a/b+/==')).toEqual({
            html: '<p>α</p>',
            css: '',
            styleRefs: [],
            imageWidth: 500,
        });
        expect(
            new URL(fetchMock.mock.calls[0][0], window.location.origin).searchParams.get('ubcq'),
        ).toBe('H4sI+a/b+/==');
    });

    it('surfaces malformed payload errors', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn().mockResolvedValue(
                new Response(JSON.stringify({ detail: 'Invalid compressed payload' }), {
                    status: 400,
                    headers: { 'content-type': 'application/problem+json' },
                }),
            ),
        );
        await expect(decodeUbcq('abcd')).rejects.toThrow('Invalid compressed payload');
    });
});

describe('image requests', () => {
    it('preserves the origin and encoded parameters in JPG counterpart URLs', () => {
        for (const path of [
            '/exp-svg?formula=x%2By%20%26%20%CE%B1',
            '/html-svg?ubcq=H4sI%2Ba%2Fb%3D%3D',
        ]) {
            const svg = new URL(path, 'https://images.example:8443');
            const jpg = new URL(jpegUrl(svg.href));
            expect(jpg.origin).toBe(svg.origin);
            expect(jpg.pathname).toBe(svg.pathname.replace(/-svg$/, '-jpg'));
            expect(jpg.search).toBe(svg.search);
        }
    });
    it('uses the development proxy without changing the encoded image payload', () => {
        vi.stubEnv('DEV', true);
        expect(imageRequestUrl('http://localhost:8086/html-svg?ubcq=a%2Bb%2F%3D')).toBe(
            '/html-svg?ubcq=a%2Bb%2F%3D',
        );
        expect(imageRequestUrl('http://localhost:8086/exp-svg?formula=x%2By')).toBe(
            '/exp-svg?formula=x%2By',
        );
        vi.stubEnv('DEV', false);
        expect(imageRequestUrl('https://images.example/html-svg?ubcq=a%2Bb')).toBe(
            'https://images.example/html-svg?ubcq=a%2Bb',
        );
    });
    it('round-trips original formula characters without double encoding', () => {
        const formula = 'x + y = 50% & α\n"quoted" #?';
        const url = new URL(formulaUrl(formula, 'https://math.example'));
        expect(url.pathname).toBe('/exp-svg');
        expect(url.searchParams.get('formula')).toBe(formula);
        expect(url.searchParams.size).toBe(1);
        expect(url.search).toContain('%2B');
    });
    it('preserves HTML/CSS and ordered repeated stylesheet parameters', () => {
        const html = '\n <p>`x + y` &amp; α</p>\n';
        const css = 'p::before { content: "a+b%"; }';
        const url = new URL(
            permalinkRequest({
                html,
                css,
                imageWidth: 720,
                styleRefs: [' https://a.example/a.css ', '', 'https://b.example/b.css?q=x+y'],
            }),
            'https://math.example',
        );
        expect(url.pathname).toBe('/ubcq');
        expect(url.searchParams.get('formula')).toBe(html);
        expect(url.searchParams.get('style')).toBe(css);
        expect(url.searchParams.get('imageWidth')).toBe('720');
        expect(url.searchParams.getAll('styleRefs')).toEqual([
            'https://a.example/a.css',
            'https://b.example/b.css?q=x+y',
        ]);
    });
    it('builds complete image URLs from raw descriptors at the configured API origin', async () => {
        vi.stubEnv('VITE_PERMAFORMULA_API_ORIGIN', 'http://localhost:8090');
        const ubcq = 'H4sI+a/b+/==';
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(ubcq)));
        const result = new URL(
            await createPermalink({ html: '<p>x</p>', css: '', styleRefs: [], imageWidth: 500 }),
        );
        expect(result.origin).toBe('http://localhost:8090');
        expect(result.pathname).toBe('/html-svg');
        expect(result.searchParams.get('ubcq')).toBe(ubcq);
        expect(result.searchParams.size).toBe(1);
    });
    it('accepts HTTPS stylesheet references without credentials', () => {
        expect(validStyleRef('https://cdn.example/a.css')).toBe(true);
        for (const url of [
            'http://cdn.example/a.css',
            'javascript:alert(1)',
            'https://user:pass@cdn.example/a.css',
            'invalid',
        ]) {
            expect(validStyleRef(url)).toBe(false);
        }
    });
    it('surfaces ProblemDetail errors and rejects invalid descriptor responses', async () => {
        const request = { html: '<p>x</p>', css: '', styleRefs: [], imageWidth: 500 };
        vi.stubGlobal(
            'fetch',
            vi.fn().mockResolvedValue(
                new Response(JSON.stringify({ detail: 'Content exceeds limit' }), {
                    status: 400,
                    headers: { 'Content-Type': 'application/problem+json' },
                }),
            ),
        );
        await expect(createPermalink(request)).rejects.toThrow('Content exceeds limit');
        for (const value of [
            '',
            'javascript:alert(1)',
            'https://images.example/html-svg?ubcq=abcd',
            'abc%2Bdef',
            'abc',
        ]) {
            vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(value)));
            await expect(createPermalink(request)).rejects.toThrow(
                'invalid compressed image descriptor',
            );
        }
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('H4sI+a/b+/==')));
        await expect(createPermalink(request)).resolves.toBe(
            new URL('/html-svg?ubcq=H4sI%2Ba%2Fb%2B%2F%3D%3D', window.location.origin).href,
        );
    });
});

describe('preview document', () => {
    it('removes active HTML and serializes CSS without closing its script', () => {
        const source = previewDocument(
            {
                html: '<p class="safe">hello</p><script>alert(1)</script><img src=x onerror="alert(2)"><iframe src="https://bad.example"></iframe><a href="javascript:alert(3)">link</a>',
                css: '</script><script>alert(4)</script>',
            },
            'https://math.example',
        );
        const doc = new DOMParser().parseFromString(source, 'text/html');
        expect(doc.body.querySelector('p')?.textContent).toBe('hello');
        expect(doc.body.querySelectorAll('script,img,iframe')).toHaveLength(0);
        expect(doc.body.querySelector('a')).toBeNull();
        expect(doc.querySelectorAll('script')).toHaveLength(2);
        expect(doc.querySelector('script')?.textContent).toContain('\\u003c/script>');
        expect(doc.querySelector('meta[http-equiv]')?.getAttribute('content')).toContain(
            "connect-src 'none'",
        );
    });
    it('includes only validated stylesheet references in order', () => {
        const source = previewDocument(
            {
                externalStylesAllowed: true,
                html: 'x',
                styleRefs: [
                    'https://a.example/a.css',
                    'http://bad.example/a.css',
                    'https://b.example/b.css',
                ],
            },
            'https://math.example',
        );
        const doc = new DOMParser().parseFromString(source, 'text/html');
        expect([...doc.querySelectorAll('link')].map((link) => link.href)).toEqual([
            'https://a.example/a.css',
            'https://b.example/b.css',
        ]);
    });
});
