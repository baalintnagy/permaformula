// @vitest-environment jsdom
import { describe, expect, it, vi, afterEach } from 'vitest';
import {
  createPermalink,
  formulaUrl,
  imageRequestUrl,
  permalinkRequest,
  validStyleRef,
} from './api';
import { previewDocument } from './preview-document';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe('image requests', () => {
  it('uses the development proxy without changing the encoded image payload', () => {
    vi.stubEnv('DEV', true);
    expect(imageRequestUrl('http://localhost:8086/image?ubcq=a%2Bb%2F%3D')).toBe(
      '/image?ubcq=a%2Bb%2F%3D',
    );
    expect(imageRequestUrl('http://localhost:8086/img?formula=x%2By')).toBe('/img?formula=x%2By');
    vi.stubEnv('DEV', false);
    expect(imageRequestUrl('https://images.example/image?ubcq=a%2Bb')).toBe(
      'https://images.example/image?ubcq=a%2Bb',
    );
  });
  it('round-trips original formula characters without double encoding', () => {
    const formula = 'x + y = 50% & α\n"quoted" #?';
    const url = new URL(formulaUrl(formula, 'https://math.example'));
    expect(url.pathname).toBe('/img');
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
    expect(url.pathname).toBe('/url');
    expect(url.searchParams.get('formula')).toBe(html);
    expect(url.searchParams.get('style')).toBe(css);
    expect(url.searchParams.get('imageWidth')).toBe('720');
    expect(url.searchParams.getAll('styleRefs')).toEqual([
      'https://a.example/a.css',
      'https://b.example/b.css?q=x+y',
    ]);
  });
  it('accepts HTTPS stylesheet references without credentials', () => {
    expect(validStyleRef('https://cdn.example/a.css')).toBe(true);
    for (const url of [
      'http://cdn.example/a.css',
      'javascript:alert(1)',
      'https://user:pass@cdn.example/a.css',
      'invalid',
    ])
      expect(validStyleRef(url)).toBe(false);
  });
  it('surfaces ProblemDetail errors and rejects invalid permalink schemes', async () => {
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
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('javascript:alert(1)')));
    await expect(createPermalink(request)).rejects.toThrow('invalid image permalink');
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response('https://images.example/image?ubcq=abc%2Bdef')),
    );
    await expect(createPermalink(request)).resolves.toBe(
      'https://images.example/image?ubcq=abc%2Bdef',
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
