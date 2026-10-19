import { formulaUrl, imageRequestUrl } from './api';
// Serialize preview transfers across library cards, including admin cards.
let tail: Promise<unknown> = Promise.resolve();

export async function libraryImageUrl(
    item: { kind: 'html' | 'formula'; ubcq: string; descriptor?: { forumla: string } },
    signal?: AbortSignal,
): Promise<string> {
    if (item.kind === 'html') {
        return `/html-svg?${new URLSearchParams({ ubcq: item.ubcq })}`;
    }
    if (item.descriptor) {
        return formulaUrl(item.descriptor.forumla);
    }
    signal?.throwIfAborted();
    const bytes = Uint8Array.from(atob(item.ubcq), (character) => character.charCodeAt(0));
    const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
    const reader = stream.getReader();
    const parts: Uint8Array[] = [];
    let size = 0;
    try {
        while (true) {
            signal?.throwIfAborted();
            const { value, done } = await reader.read();
            if (done) {
                break;
            }
            size += value.byteLength;
            if (size > 12 * 1024 * 1024) {
                throw new Error('Stored image descriptor exceeds the size limit.');
            }
            parts.push(value);
        }
    } finally {
        await reader.cancel();
    }
    const descriptor = JSON.parse(await new Blob(parts as BlobPart[]).text());
    if (typeof descriptor.forumla !== 'string') {
        throw new Error('Invalid stored formula descriptor.');
    }
    return formulaUrl(descriptor.forumla);
}

export function loadLibraryPreview(url: string, signal: AbortSignal): Promise<Blob> {
    const task = tail
        .catch(() => {})
        .then(async () => {
            for (let attempt = 0; ; attempt++) {
                signal.throwIfAborted();
                const response = await fetch(imageRequestUrl(url), { signal, credentials: 'omit' });
                if (response.ok) {
                    return response.blob();
                }
                if (![429, 503].includes(response.status) || attempt === 3) {
                    throw new Error(
                        response.status === 404
                            ? 'This image has expired or is unavailable.'
                            : 'Could not load preview. Please retry.',
                    );
                }
                const header = response.headers.get('Retry-After');
                const seconds = header === null ? NaN : Number(header);
                const delay = Number.isFinite(seconds)
                    ? seconds * 1000
                    : header
                      ? Date.parse(header) - Date.now()
                      : 1000 * (attempt + 1);
                await response.body?.cancel();
                await new Promise<void>((resolve, reject) => {
                    const abort = () => {
                        clearTimeout(timer);
                        reject(signal.reason);
                    };
                    const timer = setTimeout(
                        () => {
                            signal.removeEventListener('abort', abort);
                            resolve();
                        },
                        Math.max(250, Math.min(10000, Number.isFinite(delay) ? delay : 1000)),
                    );
                    signal.addEventListener('abort', abort, { once: true });
                    if (signal.aborted) {
                        abort();
                    }
                });
            }
        });
    tail = task;
    return task;
}
