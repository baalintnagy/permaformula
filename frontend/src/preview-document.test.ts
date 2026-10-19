// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { previewDocument } from './preview-document';

describe('external preview styles', () => {
    const input = {
        html: '<p style="color: red">Shared</p>',
        css: '@import "https://example.com/import.css"; p { color: red; }',
        styleRefs: ['https://example.com/main.css'],
    };

    it('blocks external links, imports and fonts until explicitly loaded', () => {
        const document = new DOMParser().parseFromString(previewDocument(input), 'text/html');
        expect(document.querySelector('link')).toBeNull();
        expect(
            document
                .querySelector('meta[http-equiv="Content-Security-Policy"]')!
                .getAttribute('content'),
        ).toContain("style-src 'unsafe-inline'; font-src data:;");
        expect(document.querySelector('p')!.getAttribute('style')).toBe('color: red');
    });

    it('permits HTTPS styles and fonts after loading while retaining isolation', () => {
        const document = new DOMParser().parseFromString(
            previewDocument({ ...input, externalStylesAllowed: true }),
            'text/html',
        );
        expect(document.querySelector('link')!.getAttribute('href')).toBe(input.styleRefs[0]);
        const csp = document
            .querySelector('meta[http-equiv="Content-Security-Policy"]')!
            .getAttribute('content');
        expect(csp).toContain("style-src 'unsafe-inline' https:; font-src https: data:;");
        expect(csp).toContain("connect-src 'none'");
        expect(csp).toContain("img-src 'none'");
        expect(document.body.textContent).toBe('Shared');
        expect(document.querySelector('p')!.getAttribute('style')).toBe('color: red');
    });
});
