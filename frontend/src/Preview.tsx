import { ZoomControl } from './ZoomControl';
import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { previewDocument } from './preview-document';
import { validStyleRef } from './api';

export function Preview({
    formula,
    html,
    css,
    styleRefs,
    imageWidth,
    dark = false,
}: {
    formula?: string;
    html?: string;
    css?: string;
    styleRefs?: string[];
    imageWidth?: number;
    dark?: boolean;
}) {
    const frames = useRef(new Map<string, HTMLIFrameElement>());
    const scrollContainer = useRef<HTMLDivElement>(null);
    const frameScroll = useRef<unknown>(undefined);
    const outerScroll = useRef({ left: 0, top: 0 });
    const [active, setActive] = useState<string>();
    const [pending, setPending] = useState<string>();
    const [zoom, setZoom] = useState(100);
    const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
    const [reload, setReload] = useState(0);
    const [loadedStyles, setLoadedStyles] = useState<string | null>(null);
    const styleHint = useId();
    const hasExternalStyles =
        formula === undefined &&
        ((styleRefs || []).some((ref) => validStyleRef(ref.trim())) ||
            /@import\b|@font-face\b|url\s*\(\s*["']?https:\/\//i.test((css || '') + (html || '')));
    const refsIdentity = JSON.stringify(styleRefs || []);
    const externalStyleIdentity = JSON.stringify([
        refsIdentity,
        ((css || '') + (html || '')).match(/@import[^;]*;?|url\([^)]*\)/gi) || [],
    ]);
    const stylesAllowed = loadedStyles === externalStyleIdentity;
    useEffect(() => setLoadedStyles(null), [externalStyleIdentity]);
    const document = useMemo(
        () =>
            previewDocument({
                formula,
                html,
                css,
                styleRefs: JSON.parse(refsIdentity),
                imageWidth,
                externalStylesAllowed: stylesAllowed,
            }),
        [formula, html, css, refsIdentity, imageWidth, reload, stylesAllowed],
    );

    useEffect(() => {
        const timer = window.setTimeout(() => {
            setStatus('loading');
            setPending(document);
        }, 200);
        return () => window.clearTimeout(timer);
    }, [document]);
    useEffect(() => {
        function onMessage(event: MessageEvent) {
            if (
                active &&
                event.source === frames.current.get(active)?.contentWindow &&
                event.data?.type === 'permaformula-preview-scroll'
            ) {
                frameScroll.current = event.data.scroll;
            }
            if (
                pending === document &&
                event.source === frames.current.get(pending)?.contentWindow &&
                event.data?.type === 'permaformula-preview'
            ) {
                setStatus(event.data.ok ? 'ready' : 'error');
                if (event.data.ok) {
                    // The sandbox is opaque-origin: transfer scroll state through its own script.
                    try {
                        const container = scrollContainer.current;
                        if (container) {
                            outerScroll.current = {
                                left: container.scrollLeft,
                                top: container.scrollTop,
                            };
                        }
                        frames.current.get(pending)?.contentWindow?.postMessage(
                            {
                                type: 'permaformula-restore-scroll',
                                scroll: frameScroll.current,
                            },
                            '*',
                        );
                    } catch {
                        // Restoring scroll is best-effort; a new preview must still become usable.
                    }
                    setActive(pending);
                }
                setPending(undefined);
            }
        }
        window.addEventListener('message', onMessage);
        return () => window.removeEventListener('message', onMessage);
    }, [pending, document, active]);
    useLayoutEffect(() => {
        try {
            scrollContainer.current?.scrollTo({ ...outerScroll.current, behavior: 'instant' });
        } catch {
            // Leave the browser's default position when restoration is unavailable.
        }
    }, [active]);
    useEffect(() => {
        if (formula !== undefined) {
            for (const frame of frames.current.values()) {
                frame.contentWindow?.postMessage({ type: 'permaformula-zoom', zoom }, '*');
            }
        }
    }, [zoom, active, pending, formula]);
    useEffect(() => {
        if (formula !== undefined) {
            for (const [source, frame] of frames.current) {
                frame.contentWindow?.postMessage(
                    { type: 'permaformula-theme', dark, animate: source === active },
                    '*',
                );
            }
        }
    }, [dark, active, pending, formula]);
    useEffect(() => {
        if (!pending || status !== 'loading') {
            return;
        }
        const timer = window.setTimeout(() => {
            setStatus('error');
            setPending(undefined);
        }, 15000);
        return () => window.clearTimeout(timer);
    }, [pending, status]);

    return (
        <>
            <div
                className={`preview-container ${formula === undefined ? 'html-preview' : 'formula-preview'}`}
            >
                {status === 'error' && (
                    <div className="preview-error" role="alert">
                        Preview unavailable. Check the input or use server rendering.
                    </div>
                )}
                <ZoomControl
                    className="preview-zoom"
                    value={zoom}
                    onChange={setZoom}
                    label={formula === undefined ? 'HTML preview zoom' : 'Formula preview zoom'}
                />
                <div
                    className="preview-scroll"
                    ref={scrollContainer}
                    onScroll={(event) => {
                        outerScroll.current = {
                            left: event.currentTarget.scrollLeft,
                            top: event.currentTarget.scrollTop,
                        };
                    }}
                >
                    {[active, pending]
                        .filter((source): source is string => !!source)
                        .filter((source, index, sources) => sources.indexOf(source) === index)
                        .map((source) => (
                            <iframe
                                key={source}
                                ref={(frame) => {
                                    if (frame) {
                                        frames.current.set(source, frame);
                                    } else {
                                        frames.current.delete(source);
                                    }
                                }}
                                title={`${formula === undefined ? 'HTML' : 'Formula'} live preview${source === active ? '' : ' (updating)'}`}
                                aria-hidden={source !== active}
                                tabIndex={source === active ? undefined : -1}
                                sandbox="allow-scripts"
                                referrerPolicy="no-referrer"
                                srcDoc={source}
                                onLoad={(event) => {
                                    event.currentTarget.contentWindow?.postMessage(
                                        { type: 'permaformula-zoom', zoom },
                                        '*',
                                    );
                                    event.currentTarget.contentWindow?.postMessage(
                                        { type: 'permaformula-theme', dark, animate: false },
                                        '*',
                                    );
                                }}
                                style={{
                                    ...(formula === undefined
                                        ? { width: imageWidth || 500, zoom: zoom / 100 }
                                        : {}),
                                    ...(source !== active
                                        ? {
                                              position: 'absolute',
                                              top: 0,
                                              left: 0,
                                              visibility: 'hidden',
                                              pointerEvents: 'none',
                                          }
                                        : {}),
                                }}
                            />
                        ))}
                </div>
            </div>
            {hasExternalStyles && !stylesAllowed && (
                <div className="external-styles-actions">
                    <button
                        type="button"
                        className="text-button"
                        title="External stylesheets and fonts can contact third-party hosts, revealing your IP address and request timing. Load only sources you trust."
                        aria-describedby={styleHint}
                        onClick={() => {
                            setLoadedStyles(externalStyleIdentity);
                            setReload((value) => value + 1);
                        }}
                    >
                        Load styles
                    </button>
                    <small id={styleHint}>
                        External stylesheets and fonts reveal your IP address to their hosts. Load
                        only sources you trust.
                    </small>
                </div>
            )}
        </>
    );
}
