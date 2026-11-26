// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { loadLibraryPreview } from './libraryPreview';

beforeEach(() => {
    vi.useFakeTimers();
});

afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
});

it('retries interrupted requests and body transfers with public credentials', async () => {
    const image = new Blob(['<svg/>'], { type: 'image/svg+xml' });
    const fetchMock = vi
        .fn()
        .mockRejectedValueOnce(new TypeError('Failed to fetch'))
        .mockResolvedValueOnce({
            ok: true,
            blob: vi.fn().mockRejectedValue(new TypeError('Failed to fetch')),
        })
        .mockResolvedValueOnce({ ok: true, blob: () => Promise.resolve(image) });
    vi.stubGlobal('fetch', fetchMock);
    const signal = new AbortController().signal;
    const result = loadLibraryPreview('/exp-svg?formula=x', signal);
    await vi.runAllTimersAsync();
    expect(await result).toBe(image);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    for (const [, options] of fetchMock.mock.calls) {
        expect(options).toEqual({ signal, credentials: 'omit' });
    }
});

it('limits network retries and allows the next queued preview to load', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    vi.stubGlobal('fetch', fetchMock);
    const signal = new AbortController().signal;
    const failed = expect(loadLibraryPreview('/exp-svg?formula=x', signal)).rejects.toThrow(
        'Could not load preview. Please retry.',
    );
    await vi.runAllTimersAsync();
    await failed;
    expect(fetchMock).toHaveBeenCalledTimes(4);
    fetchMock.mockResolvedValueOnce(new Response('<svg/>'));
    await expect(loadLibraryPreview('/exp-svg?formula=y', signal)).resolves.toBeInstanceOf(Blob);
    expect(fetchMock).toHaveBeenCalledTimes(5);
});

it('cancels retry backoff immediately and releases the preview queue', async () => {
    const fetchMock = vi
        .fn()
        .mockRejectedValueOnce(new TypeError('Failed to fetch'))
        .mockResolvedValueOnce(new Response('<svg/>'));
    vi.stubGlobal('fetch', fetchMock);
    const controller = new AbortController();
    const reason = new DOMException('Left the library', 'AbortError');
    const cancelled = expect(
        loadLibraryPreview('/exp-svg?formula=x', controller.signal),
    ).rejects.toBe(reason);
    const next = loadLibraryPreview('/exp-svg?formula=y', new AbortController().signal);
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    controller.abort(reason);
    await cancelled;
    await expect(next).resolves.toBeInstanceOf(Blob);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(vi.getTimerCount()).toBe(0);
});

it.each([400, 404])('does not retry permanent HTTP %i errors', async (status) => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(
        loadLibraryPreview('/exp-svg?formula=x', new AbortController().signal),
    ).rejects.toThrow(
        status === 404
            ? 'This image has expired or is unavailable.'
            : 'Could not load preview. Please retry.',
    );
    expect(fetchMock).toHaveBeenCalledTimes(1);
});
