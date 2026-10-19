export function formulaUrl(
    formula: string,
    origin = import.meta.env.VITE_PERMAFORMULA_API_ORIGIN || window.location.origin,
): string {
    const url = new URL('/exp-svg', origin);
    url.searchParams.set('formula', formula);
    return url.href;
}

export function jpegUrl(svgUrl: string): string {
    const url = new URL(svgUrl, window.location.origin);
    url.pathname = url.pathname.replace(/-svg$/, '-jpg');
    return url.href;
}

export interface ComposerRequest {
    html: string;
    css: string;
    styleRefs: string[];
    imageWidth: number;
}

export function extractUbcq(input: string): string {
    const value = input.trim();
    if (/^[A-Za-z0-9+/]+={0,2}$/.test(value)) {
        return value;
    }
    try {
        const url = new URL(value, window.location.origin);
        const query = url.search
            .slice(1)
            .split('&')
            .find((part) => part.split('=')[0] === 'ubcq');
        if (query) {
            // Decode percent escapes without changing literal Base64 plus signs to spaces.
            const payload = decodeURIComponent(query.slice(query.indexOf('=') + 1));
            if (/^[A-Za-z0-9+/]+={0,2}$/.test(payload)) {
                return payload;
            }
        }
    } catch {
        /* Report a useful input error below. */
    }
    throw new Error('Paste a UBCQ value or a URL containing a ubcq query parameter.');
}

export async function decodeUbcq(input: string, signal?: AbortSignal): Promise<ComposerRequest> {
    const response = await fetch(
        `/ubcq-decode?${new URLSearchParams({ ubcq: extractUbcq(input) })}`,
        {
            signal,
            headers: { Accept: 'application/json' },
        },
    );
    if (!response.ok) {
        throw new Error(await responseError(response));
    }
    const data = await response.json();
    if (
        typeof data.forumla !== 'string' ||
        (data.style != null && typeof data.style !== 'string') ||
        (data.styleRefs != null &&
            (!Array.isArray(data.styleRefs) ||
                !data.styleRefs.every((ref: unknown) => typeof ref === 'string'))) ||
        (data.imageWidth != null &&
            (!Number.isInteger(data.imageWidth) || data.imageWidth < 220 || data.imageWidth > 4000))
    ) {
        throw new Error('The server returned an invalid image descriptor.');
    }
    return {
        html: data.forumla,
        css: data.style ?? '',
        styleRefs: data.styleRefs ?? [],
        imageWidth: data.imageWidth ?? 500,
    };
}

export function imageRequestUrl(permalink: string): string {
    const url = new URL(permalink, window.location.origin);
    // Vite proxies image endpoints; direct API fetches would require CORS in development.
    if (import.meta.env.DEV && ['/html-svg', '/exp-svg'].includes(url.pathname)) {
        return url.pathname + url.search;
    }
    return url.href;
}

export function validStyleRef(value: string): boolean {
    try {
        const url = new URL(value);
        return url.protocol === 'https:' && !url.username && !url.password;
    } catch {
        return false;
    }
}

export function permalinkRequest(request: ComposerRequest): string {
    const params = new URLSearchParams({
        formula: request.html,
        style: request.css,
        imageWidth: String(request.imageWidth),
    });
    for (const ref of request.styleRefs) {
        if (ref.trim()) {
            params.append('styleRefs', ref.trim());
        }
    }
    return `/ubcq?${params}`;
}

export async function responseError(response: Response): Promise<string> {
    try {
        const contentType = response.headers.get('content-type') || '';
        if (contentType.includes('json')) {
            const problem = await response.json();
            return problem.detail || problem.title || `Request failed (${response.status}).`;
        }
    } catch {
        /* Use the status for non-ProblemDetail responses. */
    }
    return `Request failed (${response.status}). Please check the input and try again.`;
}

export async function createPermalink(
    request: ComposerRequest,
    signal?: AbortSignal,
): Promise<string> {
    const response = await fetch(permalinkRequest(request), {
        signal,
        headers: { Accept: 'text/plain' },
    });
    if (!response.ok) {
        throw new Error(await responseError(response));
    }
    const result = (await response.text()).trim();
    if (!/^[A-Za-z0-9+/]+={0,2}$/.test(result) || result.length % 4 !== 0) {
        throw new Error('The server returned an invalid compressed image descriptor.');
    }
    const url = new URL(
        '/html-svg',
        import.meta.env.VITE_PERMAFORMULA_API_ORIGIN || window.location.origin,
    );
    url.searchParams.set('ubcq', result);
    return url.href;
}
