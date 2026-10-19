import { ZoomControl } from './ZoomControl';
import { useEffect, useRef, useState } from 'react';
import { jpegUrl, responseError } from './api';
import { SourceCode } from './SourceCode';
import { libraryImageUrl, loadLibraryPreview } from './libraryPreview';
export interface LibraryItem {
    id: string;
    kind: 'html' | 'formula';
    descriptor: { forumla: string; style?: string; styleRefs?: string[]; imageWidth?: number };
    createdAt: number;
    ubcq?: string;
}
interface LibrarySummary {
    id: string;
    kind: 'html' | 'formula';
    excerpt: string | null;
    ubcq: string;
    createdAt: number;
    descriptor?: LibraryItem['descriptor'];
}

function LibrarySource({
    item,
    expanded,
    onToggle,
}: {
    item: LibrarySummary;
    expanded: boolean;
    onToggle: () => void;
}) {
    const [detail, setDetail] = useState<LibraryItem | null>(null);
    const [error, setError] = useState('');
    const [retry, setRetry] = useState(0);
    const [zoom, setZoom] = useState(100);
    const descriptor = item.descriptor ?? detail?.descriptor;
    useEffect(() => {
        setDetail(null);
        setError('');
        if (!expanded || item.descriptor) {
            return;
        }
        const controller = new AbortController();
        fetch(`/library-item?id=${encodeURIComponent(item.id)}`, { signal: controller.signal })
            .then(async (response) => {
                if (!response.ok) {
                    throw new Error(await responseError(response));
                }
                const value: LibraryItem = await response.json();
                if (!controller.signal.aborted) {
                    setDetail(value);
                }
            })
            .catch((failure) => {
                if (!controller.signal.aborted) {
                    setError(failure.message);
                }
            });
        return () => controller.abort();
    }, [item.id, item.descriptor, expanded, retry]);
    return (
        <div className="library-source">
            <ZoomControl
                value={zoom}
                onChange={setZoom}
                label={item.kind === 'html' ? 'HTML and CSS source zoom' : 'Expression source zoom'}
            />
            <SourceCode
                zoom={zoom}
                value={descriptor?.forumla ?? item.excerpt ?? 'Source available on request.'}
                language={item.kind === 'html' ? 'html' : 'formula'}
            />
            {!item.descriptor && (
                <button className="text-button" onClick={onToggle} aria-expanded={expanded}>
                    {expanded ? 'Hide full source' : 'Show full source'}
                </button>
            )}
            {expanded && !descriptor && !error && <p role="status">Loading source...</p>}
            {error && (
                <div role="alert">
                    {error}{' '}
                    <button className="text-button" onClick={() => setRetry((value) => value + 1)}>
                        Retry source
                    </button>
                </div>
            )}
            {descriptor && item.kind === 'html' && (
                <div className="library-css">
                    <SourceCode value={descriptor.style || ''} language="css" zoom={zoom} />
                    <div className="library-layout">
                        <p>Canvas: {descriptor.imageWidth ?? 500}px</p>
                        <pre className="library-style-refs">
                            {descriptor.styleRefs?.join('\n') || 'No external stylesheets'}
                        </pre>
                    </div>
                </div>
            )}
        </div>
    );
}

function LibraryViewer({ item }: { item: LibrarySummary }) {
    const [preview, setPreview] = useState('');
    const [error, setError] = useState('');
    const [retry, setRetry] = useState(0);
    const [zoom, setZoom] = useState(100);
    const [dimensions, setDimensions] = useState({ width: 0, height: 0 });
    const [availableWidth, setAvailableWidth] = useState(0);
    const canvas = useRef<HTMLDivElement>(null);
    useEffect(() => {
        const controller = new AbortController();
        let objectUrl = '';
        setPreview('');
        setError('');
        setDimensions({ width: 0, height: 0 });
        const observer = new IntersectionObserver(
            (entries) => {
                if (!entries.some((entry) => entry.isIntersecting)) {
                    return;
                }
                observer.disconnect();
                libraryImageUrl(item, controller.signal)
                    .then((url) => loadLibraryPreview(url, controller.signal))
                    .then((blob) => {
                        if (controller.signal.aborted) {
                            return;
                        }
                        objectUrl = URL.createObjectURL(blob);
                        setPreview(objectUrl);
                    })
                    .catch((failure) => {
                        if (!controller.signal.aborted) {
                            setError(
                                failure instanceof Error
                                    ? failure.message
                                    : 'Could not load preview. Please retry.',
                            );
                        }
                    });
            },
            { rootMargin: '200px' },
        );
        if (canvas.current) {
            observer.observe(canvas.current);
        }
        return () => {
            observer.disconnect();
            controller.abort();
            if (objectUrl) {
                URL.revokeObjectURL(objectUrl);
            }
        };
    }, [item.id, item.ubcq, item.kind, retry]);
    useEffect(() => {
        const element = canvas.current;
        if (!element) {
            return;
        }
        const observer = new ResizeObserver(() =>
            setAvailableWidth(Math.max(1, element.clientWidth - 48)),
        );
        observer.observe(element);
        return () => observer.disconnect();
    }, []);
    const fit =
        dimensions.width && availableWidth
            ? Math.min(1, availableWidth / dimensions.width, 320 / dimensions.height)
            : 1;
    return (
        <div className="library-viewer">
            <ZoomControl value={zoom} onChange={setZoom} label="Library SVG zoom" />
            <div className="library-canvas" ref={canvas}>
                {error ? (
                    <div role="alert">
                        {error}{' '}
                        <button
                            className="text-button"
                            onClick={() => setRetry((value) => value + 1)}
                        >
                            Retry preview
                        </button>
                    </div>
                ) : !preview ? (
                    <p role="status">Loading preview…</p>
                ) : (
                    <a
                        className={`library-preview${item.kind === 'formula' ? ' library-preview-formula' : ''}`}
                        href={`/?libraryItem=${item.id}#${item.kind === 'formula' ? 'formula' : 'composer'}`}
                        aria-label="Open image in editor"
                    >
                        <img
                            loading="lazy"
                            src={preview}
                            onError={() => setError('Could not display preview. Please retry.')}
                            alt={
                                item.kind === 'formula'
                                    ? item.descriptor?.forumla || item.excerpt || 'Rendered formula'
                                    : 'Rendered HTML composition'
                            }
                            onLoad={(event) =>
                                setDimensions({
                                    width: event.currentTarget.naturalWidth,
                                    height: event.currentTarget.naturalHeight,
                                })
                            }
                            style={
                                dimensions.width
                                    ? {
                                          width: (dimensions.width * fit * zoom) / 100,
                                          height: (dimensions.height * fit * zoom) / 100,
                                      }
                                    : undefined
                            }
                        />
                    </a>
                )}
            </div>
        </div>
    );
}

function LibraryDownloads({ item }: { item: LibrarySummary }) {
    const [url, setUrl] = useState('');
    const [error, setError] = useState('');
    const [retry, setRetry] = useState(0);
    useEffect(() => {
        const controller = new AbortController();
        libraryImageUrl(item, controller.signal)
            .then((value) => {
                if (!controller.signal.aborted) {
                    setUrl(value);
                    setError('');
                }
            })
            .catch(() => {
                if (!controller.signal.aborted) {
                    setError('Could not prepare download links.');
                }
            });
        return () => controller.abort();
    }, [item, retry]);
    if (error) {
        return (
            <button className="text-button" onClick={() => setRetry((value) => value + 1)}>
                {error} Retry
            </button>
        );
    }
    return (
        <>
            <a
                className="text-button"
                href={url || undefined}
                target="_blank"
                rel="noopener noreferrer"
                aria-disabled={!url}
            >
                Download SVG
            </a>
            <a
                className="text-button"
                href={url ? jpegUrl(url) : undefined}
                target="_blank"
                rel="noopener noreferrer"
                aria-disabled={!url}
            >
                Download JPG
            </a>
        </>
    );
}

export function Library({
    admin = false,
    token = '',
    onUnauthorized,
}: {
    admin?: boolean;
    token?: string;
    onUnauthorized?: () => void;
}) {
    const [items, setItems] = useState<LibrarySummary[]>([]);
    const [cursors, setCursors] = useState(['']);
    const cursor = cursors[cursors.length - 1];
    const [nextCursor, setNextCursor] = useState<string | null>(null);
    const [activeSource, setActiveSource] = useState('');
    const [busy, setBusy] = useState(true);
    const [error, setError] = useState('');
    const [deleting, setDeleting] = useState('');
    const [deleteError, setDeleteError] = useState('');
    const [revision, setRevision] = useState(0);
    async function deleteItem(id: string) {
        if (
            deleting ||
            !window.confirm(
                'Delete this cached image? It can be regenerated by rendering its source again.',
            )
        ) {
            return;
        }
        setDeleting(id);
        setDeleteError('');
        try {
            const response = await fetch(`/admin/cache-item?id=${encodeURIComponent(id)}`, {
                method: 'DELETE',
                headers: { Authorization: `Bearer ${token}` },
            });
            if (response.status === 401) {
                onUnauthorized?.();
                return;
            }
            if (!response.ok) {
                throw new Error(await responseError(response));
            }
            if (items.length === 1 && cursors.length > 1) {
                setCursors((value) => value.slice(0, -1));
            } else {
                setRevision((value) => value + 1);
            }
        } catch (failure) {
            setDeleteError(failure instanceof Error ? failure.message : 'Deletion failed.');
        } finally {
            setDeleting('');
        }
    }
    useEffect(() => {
        const controller = new AbortController();
        setBusy(true);
        setError('');
        setActiveSource('');
        fetch(`/library-items?limit=12${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, {
            signal: controller.signal,
        })
            .then(async (response) => {
                if (!response.ok) {
                    throw new Error(await responseError(response));
                }
                const page: { items: LibrarySummary[]; nextCursor: string | null } =
                    await response.json();
                if (!controller.signal.aborted) {
                    setItems(page.items);
                    setNextCursor(page.nextCursor);
                }
            })
            .catch((error) => {
                if (!controller.signal.aborted) {
                    setError(error.message);
                }
            })
            .finally(() => {
                if (!controller.signal.aborted) {
                    setBusy(false);
                }
            });
        return () => controller.abort();
    }, [cursor, revision]);
    return (
        <main>
            <div className="intro">
                <span className="eyebrow">MADE WITH PERMAFORMULA</span>
                <h1>
                    Rendered <span>library</span>
                </h1>
                <p>Explore shared creations. Open an image to make it your own.</p>
            </div>
            {deleteError && (
                <div className="error-message" role="alert">
                    {deleteError}
                </div>
            )}
            {busy ? (
                <p role="status">Loading images...</p>
            ) : error ? (
                <div className="error-message" role="alert">
                    {error}
                    <button className="text-button" onClick={() => window.location.reload()}>
                        Retry
                    </button>
                </div>
            ) : (
                <>
                    {!items.length && (
                        <div className="panel library-empty">
                            No images here yet. <a href="/#composer">Create a rendered image</a> to
                            add it to the library.
                        </div>
                    )}
                    <div className="library-list">
                        {items.map((item) => (
                            <article className="panel library-item" key={item.id}>
                                <LibraryViewer item={item} />
                                <LibrarySource
                                    item={item}
                                    expanded={activeSource === item.id}
                                    onToggle={() =>
                                        setActiveSource((value) =>
                                            value === item.id ? '' : item.id,
                                        )
                                    }
                                />
                                <div className="library-card-footer">
                                    <div className="library-card-info">
                                        <span>
                                            {item.kind === 'formula'
                                                ? 'Simple expression'
                                                : 'HTML composition'}
                                        </span>
                                        <time dateTime={new Date(item.createdAt).toISOString()}>
                                            {new Date(item.createdAt).toLocaleDateString()}
                                        </time>
                                    </div>
                                    <div className="library-card-actions">
                                        <LibraryDownloads item={item} />
                                        <a
                                            className="text-button"
                                            href={`/?libraryItem=${item.id}#${item.kind === 'formula' ? 'formula' : 'composer'}`}
                                        >
                                            Open in editor
                                        </a>
                                        {admin && (
                                            <button
                                                className="text-button delete-button"
                                                disabled={!token || !!deleting}
                                                onClick={() => void deleteItem(item.id)}
                                            >
                                                {deleting === item.id ? 'Deleting…' : 'Delete'}
                                            </button>
                                        )}
                                    </div>
                                </div>
                            </article>
                        ))}
                    </div>
                    <div className="composer-actions library-pagination">
                        <button
                            className="text-button"
                            disabled={cursors.length === 1}
                            onClick={() => setCursors((value) => value.slice(0, -1))}
                        >
                            Previous
                        </button>
                        <span>Page {cursors.length}</span>
                        <button
                            className="text-button"
                            disabled={!nextCursor}
                            onClick={() => {
                                if (nextCursor) {
                                    setCursors((value) => [...value, nextCursor]);
                                }
                            }}
                        >
                            Next
                        </button>
                    </div>
                </>
            )}
        </main>
    );
}
