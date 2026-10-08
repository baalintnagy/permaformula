export function formulaUrl(
  formula: string,
  origin = import.meta.env.VITE_PERMMATH_API_ORIGIN || window.location.origin,
): string {
  const url = new URL('/exp', origin);
  url.searchParams.set('formula', formula);
  return url.href;
}

export interface ComposerRequest {
  html: string;
  css: string;
  styleRefs: string[];
  imageWidth: number;
}

export function imageRequestUrl(permalink: string): string {
  const url = new URL(permalink, window.location.origin);
  // Vite proxies image endpoints; direct API fetches would require CORS in development.
  if (import.meta.env.DEV && ['/html', '/exp'].includes(url.pathname))
    return url.pathname + url.search;
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
    if (ref.trim()) params.append('styleRefs', ref.trim());
  }
  return `/url?${params}`;
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
  if (!response.ok) throw new Error(await responseError(response));
  const result = (await response.text()).trim();
  const url = new URL(result, window.location.origin);
  if (
    !['http:', 'https:'].includes(url.protocol) ||
    url.pathname !== '/html' ||
    !url.searchParams.has('ubcq')
  ) {
    throw new Error('The server returned an invalid SVG permalink.');
  }
  return url.href;
}
