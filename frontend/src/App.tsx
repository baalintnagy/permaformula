import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { Library, type LibraryItem } from './Library';
import { Icon } from './Icon';
import { SiteHeader } from './SiteHeader';
import { useTheme } from './useTheme';
import { ZoomControl } from './ZoomControl';
import { Editor } from './Editor';
import { Preview } from './Preview';
import {
    createPermalink,
    decodeUbcq,
    formulaUrl,
    imageRequestUrl,
    jpegUrl,
    responseError,
    validStyleRef,
} from './api';

const initialFormula = 'sum_(i=1)^n i^3 = ((n(n+1))/2)^2';
const initialHtml = `<div class="math-card">
  <span class="eyebrow">MATHEMATICAL IDENTITY</span>
  <h1>The sum of cubes</h1>
  <p>The sum has a closed form:</p>
  <div class="equation">
    \`sum_(i=1)^n i^3 = ((n(n+1))/2)^2\`
  </div>
  <p class="caption">The sum of the first n cubes equals
    the square of the n-th triangular number.</p>
</div>`;
const initialCss = `body { margin: 0; padding: 0; }
.math-card {
  padding: 48px;
  background: #edf3fb;
  color: #263b54;
  font-family: Georgia, serif;
}
.eyebrow {
  font: 11px Arial, sans-serif;
  letter-spacing: 2px;
  color: #496f9f;
}
h1 { font-size: 36px; margin: 18px 0; }
p { line-height: 1.7; }
.equation {
  padding: 24px 0;
  font-size: 25px;
}
.caption { color: #687d96; font-size: 14px; }`;

function CopyUrl({ url, label }: { url: string; label: string }) {
    const id = useId();
    const [copied, setCopied] = useState(false);
    const [error, setError] = useState(false);
    const input = useRef<HTMLTextAreaElement>(null);
    useEffect(() => {
        const field = input.current;
        if (!field) {
            return;
        }
        const resize = () => {
            field.style.height = 'auto';
            field.style.height = `${field.scrollHeight}px`;
        };
        const observer = new ResizeObserver(resize);
        observer.observe(field);
        resize();
        return () => observer.disconnect();
    }, [url]);
    useEffect(() => {
        setCopied(false);
        setError(false);
    }, [url]);
    useEffect(() => {
        if (!copied) {
            return;
        }
        const timer = window.setTimeout(() => setCopied(false), 2000);
        return () => window.clearTimeout(timer);
    }, [copied]);
    async function copy() {
        try {
            await navigator.clipboard.writeText(url);
            setCopied(true);
        } catch {
            setError(true);
            input.current?.focus();
            input.current?.select();
        }
    }
    return (
        <div className="url-output">
            <label htmlFor={id}>{label}</label>
            <div className="url-row">
                <textarea
                    id={id}
                    ref={input}
                    readOnly
                    rows={1}
                    value={url}
                    onFocus={(event) => event.target.select()}
                />
                <button
                    className="copy-button"
                    type="button"
                    onClick={copy}
                    disabled={!url}
                    aria-label={`Copy ${label}`}
                >
                    <Icon name={copied ? 'check' : 'copy'} />
                    <span>{copied ? 'Copied' : 'Copy'}</span>
                </button>
            </div>
            <span className="copy-feedback" role="status">
                {error
                    ? 'Select and copy the URL manually.'
                    : copied
                      ? 'URL copied to clipboard.'
                      : ''}
            </span>
        </div>
    );
}

function ImageUrls({
    svgUrl,
    label,
    previousInput = false,
    highlight = false,
}: {
    svgUrl: string;
    label: string;
    previousInput?: boolean;
    highlight?: boolean;
}) {
    return (
        <div className="permalink-result panel">
            {(['SVG', 'JPG'] as const).map((format) => {
                const url = format === 'SVG' ? svgUrl : jpegUrl(svgUrl);
                return (
                    <div className="image-url-option" key={format}>
                        <CopyUrl
                            url={url}
                            label={`${label} ${format} URL${previousInput ? ' (previous input)' : ''}`}
                        />
                        <a
                            href={url}
                            target="_blank"
                            rel="noopener noreferrer"
                            className={`text-button${highlight ? ' result-highlight' : ''}`}
                        >
                            Open {format}
                            <Icon name="external" />
                        </a>
                    </div>
                );
            })}
        </div>
    );
}

function AsciiMathLink() {
    return (
        <a
            className="asciimath-link"
            href="https://asciimath.org/"
            target="_blank"
            rel="noreferrer"
        >
            AsciiMath
        </a>
    );
}

function PanelHeading({
    title,
    detail,
    badge,
}: {
    title: string;
    detail?: ReactNode;
    badge?: string;
}) {
    return (
        <div className="panel-heading">
            <span>
                {title}
                {detail && <small>{detail}</small>}
            </span>
            {badge && <span className="badge">{badge}</span>}
        </div>
    );
}

export function App() {
    const [library, setLibrary] = useState(window.location.hash === '#library');
    useEffect(() => {
        const navigate = () => setLibrary(window.location.hash === '#library');
        window.addEventListener('hashchange', navigate);
        return () => window.removeEventListener('hashchange', navigate);
    }, []);
    const { dark, setThemeChoice } = useTheme();
    const [formula, setFormula] = useState(initialFormula);
    const [html, setHtml] = useState(initialHtml);
    const [css, setCss] = useState(initialCss);
    const [styleRefs, setStyleRefs] = useState<string[]>([]);
    const [imageWidth, setImageWidth] = useState('500');
    const [activeEditor, setActiveEditor] = useState<'html' | 'css'>('html');
    const [decodeInput, setDecodeInput] = useState('');
    const [decodeBusy, setDecodeBusy] = useState(false);
    const [decodeError, setDecodeError] = useState('');
    const [decodeRevision, setDecodeRevision] = useState(0);
    const decodeAbort = useRef<AbortController | null>(null);
    const decodeLock = useRef(false);
    const composerPreview = useRef<HTMLDivElement>(null);
    const [permalink, setPermalink] = useState('');
    const [generatedInput, setGeneratedInput] = useState('');
    const [renderedZoom, setRenderedZoom] = useState(100);
    const [composerBusy, setComposerBusy] = useState(false);
    const [composerError, setComposerError] = useState('');
    const [generatedImage, setGeneratedImage] = useState<{
        url: string;
        width: number;
        height: number;
        revision: number;
    } | null>(null);
    const generatedObject = useRef('');
    const generationRevision = useRef(0);
    const composerAbort = useRef<AbortController | null>(null);
    const composerLock = useRef(false);
    const width = Number(imageWidth);
    const widthValid = Number.isInteger(width) && width >= 220 && width <= 4000;
    const refsValid = styleRefs.every((ref) => !ref.trim() || validStyleRef(ref.trim()));
    const inputIdentity = JSON.stringify([html, css, styleRefs, imageWidth]);
    const expUrl = formulaUrl(formula);

    useEffect(
        () => () => {
            composerAbort.current?.abort();
            decodeAbort.current?.abort();
            if (generatedObject.current) {
                URL.revokeObjectURL(generatedObject.current);
            }
        },
        [],
    );

    useEffect(() => {
        const id = new URLSearchParams(window.location.search).get('libraryItem');
        if (!id) {
            return;
        }
        const controller = new AbortController();
        fetch(`/library-item?${new URLSearchParams({ id })}`, { signal: controller.signal })
            .then(async (response) => {
                if (!response.ok) {
                    throw new Error(await responseError(response));
                }
                const item: LibraryItem = await response.json();
                controller.signal.throwIfAborted();
                if (item.kind === 'formula') {
                    setFormula(item.descriptor.forumla);
                    document.getElementById('formula')?.scrollIntoView({ behavior: 'smooth' });
                } else {
                    const request = {
                        html: item.descriptor.forumla,
                        css: item.descriptor.style ?? '',
                        styleRefs: item.descriptor.styleRefs ?? [],
                        imageWidth: item.descriptor.imageWidth ?? 500,
                    };
                    setHtml(request.html);
                    setCss(request.css);
                    setStyleRefs(request.styleRefs);
                    setImageWidth(String(request.imageWidth));
                    setActiveEditor('html');
                    setDecodeRevision((revision) => revision + 1);
                    const restoredInput = JSON.stringify([
                        request.html,
                        request.css,
                        request.styleRefs,
                        String(request.imageWidth),
                    ]);
                    composerLock.current = true;
                    composerAbort.current = controller;
                    setComposerBusy(true);
                    setComposerError('');
                    try {
                        const url = item.ubcq
                            ? new URL(
                                  `/html-svg?${new URLSearchParams({ ubcq: item.ubcq })}`,
                                  import.meta.env.VITE_PERMAFORMULA_API_ORIGIN ||
                                      window.location.origin,
                              ).href
                            : await createPermalink(request, controller.signal);
                        controller.signal.throwIfAborted();
                        setPermalink(url);
                        setGeneratedInput(restoredInput);
                        await showGeneratedImage(url, restoredInput, controller.signal);
                    } catch (error) {
                        if (!controller.signal.aborted) {
                            setComposerError(
                                error instanceof Error
                                    ? error.message
                                    : 'Unable to load the saved SVG.',
                            );
                        }
                    } finally {
                        if (!controller.signal.aborted) {
                            composerLock.current = false;
                            setComposerBusy(false);
                        }
                    }
                }
                window.history.replaceState(
                    null,
                    '',
                    window.location.pathname + window.location.hash,
                );
            })
            .catch((error) => {
                if (!controller.signal.aborted) {
                    setDecodeError(error.message);
                }
            });
        return () => controller.abort();
    }, []);

    async function decodeAndEdit() {
        if (decodeLock.current) {
            return;
        }
        decodeLock.current = true;
        setDecodeBusy(true);
        setDecodeError('');
        const controller = new AbortController();
        decodeAbort.current = controller;
        try {
            const decoded = await decodeUbcq(decodeInput, controller.signal);
            if (controller.signal.aborted) {
                return;
            }
            setHtml(decoded.html);
            setCss(decoded.css);
            setStyleRefs(decoded.styleRefs);
            setImageWidth(String(decoded.imageWidth));
            setActiveEditor('html');
            setDecodeRevision((revision) => revision + 1);
        } catch (error) {
            if (!controller.signal.aborted) {
                setDecodeError(
                    error instanceof Error ? error.message : 'Unable to decode the UBCQ.',
                );
            }
        } finally {
            decodeLock.current = false;
            setDecodeBusy(false);
        }
    }

    useEffect(() => {
        if (!decodeRevision) {
            return;
        }
        const targets = [document.querySelector('.composer-editors'), composerPreview.current];
        for (const target of targets) {
            target?.classList.remove('result-highlight');
            if (target instanceof HTMLElement) {
                void target.offsetWidth;
            }
            target?.classList.add('result-highlight');
        }
        composerPreview.current?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }, [decodeRevision]);

    async function showGeneratedImage(url: string, submittedInput: string, signal: AbortSignal) {
        const response = await fetch(imageRequestUrl(url), {
            signal: signal,
            credentials: 'omit',
            headers: { Accept: 'image/svg+xml' },
        });
        if (!response.ok) {
            throw new Error(await responseError(response));
        }
        if (!response.headers.get('content-type')?.includes('image/svg+xml')) {
            throw new Error('The server did not return an SVG.');
        }
        const blob = await response.blob();
        if (signal.aborted) {
            return;
        }
        const objectUrl = URL.createObjectURL(blob);
        const svg = new DOMParser().parseFromString(
            await blob.text(),
            'image/svg+xml',
        ).documentElement;
        const nativeWidth = Number(svg.getAttribute('width'));
        const nativeHeight = Number(svg.getAttribute('height'));
        if (
            !Number.isFinite(nativeWidth) ||
            nativeWidth <= 0 ||
            !Number.isFinite(nativeHeight) ||
            nativeHeight <= 0
        ) {
            URL.revokeObjectURL(objectUrl);
            throw new Error('The returned SVG has invalid dimensions.');
        }
        if (signal.aborted) {
            URL.revokeObjectURL(objectUrl);
            return;
        }
        if (generatedObject.current) {
            URL.revokeObjectURL(generatedObject.current);
        }
        generatedObject.current = objectUrl;
        setGeneratedImage({
            url: objectUrl,
            width: nativeWidth,
            height: nativeHeight,
            revision: ++generationRevision.current,
        });
        setPermalink(url);
        setGeneratedInput(submittedInput);
    }

    async function generateImage() {
        if (composerLock.current) {
            return;
        }
        composerLock.current = true;
        setComposerBusy(true);
        setComposerError('');
        const controller = new AbortController();
        composerAbort.current = controller;
        const submittedInput = inputIdentity;
        try {
            const url = await createPermalink(
                { html, css, styleRefs, imageWidth: width },
                controller.signal,
            );
            await showGeneratedImage(url, submittedInput, controller.signal);
        } catch (error) {
            if (!controller.signal.aborted) {
                setComposerError(
                    error instanceof Error ? error.message : 'Unable to generate the SVG.',
                );
            }
        } finally {
            composerLock.current = false;
            setComposerBusy(false);
        }
    }

    function updateRef(index: number, value: string) {
        setStyleRefs((current) => current.map((ref, i) => (i === index ? value : ref)));
    }
    function moveRef(index: number, delta: number) {
        setStyleRefs((current) => {
            const next = [...current];
            [next[index], next[index + delta]] = [next[index + delta], next[index]];
            return next;
        });
    }

    return (
        <>
            <SiteHeader dark={dark} onToggleTheme={() => setThemeChoice(dark ? 'light' : 'dark')} />
            {library ? (
                <Library />
            ) : (
                <main>
                    <div className="intro">
                        <span className="eyebrow">FORMULA TO SCALABLE VECTOR GRAPHICS</span>
                        <h1>
                            <span>Create</span> and <span>publish</span> formulae
                        </h1>
                        <p>
                            Write <AsciiMathLink />. Define style. Share permanent SVG and JPEG
                            images.
                        </p>
                    </div>

                    <section id="formula" className="workspace" aria-labelledby="formula-heading">
                        <div className="section-heading">
                            <div className="section-icon">
                                <Icon name="formula" />
                            </div>
                            <div>
                                <h2 id="formula-heading">Simple expression</h2>
                            </div>
                            <span className="section-tag">
                                <AsciiMathLink /> → SVG
                            </span>
                        </div>
                        <div className="workspace-grid formula-grid">
                            <div className="panel expression-editor-panel">
                                <PanelHeading
                                    title="Expression"
                                    detail={<AsciiMathLink />}
                                    badge="SOURCE"
                                />
                                <Editor
                                    label="AsciiMath formula"
                                    language="asciimath"
                                    value={formula}
                                    onChange={setFormula}
                                    dark={dark}
                                />
                            </div>
                            <div className="panel preview-panel">
                                <PanelHeading title="Live preview" badge="SVG" />
                                <Preview formula={formula} dark={dark} />
                                <div className="panel-note">Updates as you type.</div>
                            </div>
                        </div>
                        <ImageUrls svgUrl={expUrl} label="Expression" />
                    </section>

                    <section id="composer" className="workspace" aria-labelledby="composer-heading">
                        <div className="section-heading">
                            <div className="section-icon">
                                <Icon name="code" />
                            </div>
                            <div>
                                <h2 id="composer-heading">Formula + HTML + CSS</h2>
                            </div>
                            <span className="section-tag">
                                <AsciiMathLink /> + HTML + CSS → SVG
                            </span>
                        </div>
                        <div className="workspace-grid composer-grid">
                            <div className="composer-editors">
                                <div className="panel">
                                    <div className="editor-toolbar">
                                        <div
                                            className="editor-tabs"
                                            role="tablist"
                                            aria-label="Source language"
                                        >
                                            <button
                                                id="html-tab"
                                                role="tab"
                                                aria-selected={activeEditor === 'html'}
                                                aria-controls="html-editor-panel"
                                                tabIndex={activeEditor === 'html' ? 0 : -1}
                                                onKeyDown={(event) => {
                                                    if (
                                                        ['ArrowLeft', 'ArrowRight'].includes(
                                                            event.key,
                                                        )
                                                    ) {
                                                        setActiveEditor('css');
                                                        document.getElementById('css-tab')?.focus();
                                                    }
                                                }}
                                                onClick={() => setActiveEditor('html')}
                                            >
                                                HTML<span>.html</span>
                                            </button>
                                            <button
                                                id="css-tab"
                                                role="tab"
                                                aria-selected={activeEditor === 'css'}
                                                aria-controls="css-editor-panel"
                                                tabIndex={activeEditor === 'css' ? 0 : -1}
                                                onKeyDown={(event) => {
                                                    if (
                                                        ['ArrowLeft', 'ArrowRight'].includes(
                                                            event.key,
                                                        )
                                                    ) {
                                                        setActiveEditor('html');
                                                        document
                                                            .getElementById('html-tab')
                                                            ?.focus();
                                                    }
                                                }}
                                                onClick={() => setActiveEditor('css')}
                                            >
                                                CSS<span>.css</span>
                                            </button>
                                        </div>
                                        <div className="canvas-toolbar">
                                            <label>
                                                Canvas width{' '}
                                                <div className="width-input">
                                                    <input
                                                        type="number"
                                                        min="220"
                                                        max="4000"
                                                        step="1"
                                                        value={imageWidth}
                                                        onChange={(event) =>
                                                            setImageWidth(event.target.value)
                                                        }
                                                        aria-invalid={!widthValid}
                                                    />
                                                    <span>px</span>
                                                </div>
                                            </label>
                                        </div>
                                    </div>
                                    {!widthValid && (
                                        <p className="field-error width-error">
                                            Use a whole number from 220 to 4000 pixels.
                                        </p>
                                    )}

                                    <div
                                        id="html-editor-panel"
                                        role="tabpanel"
                                        aria-labelledby="html-tab"
                                        hidden={activeEditor !== 'html'}
                                    >
                                        <Editor
                                            label="HTML source"
                                            language="html"
                                            value={html}
                                            onChange={setHtml}
                                            dark={dark}
                                        />
                                    </div>
                                    <div
                                        id="css-editor-panel"
                                        role="tabpanel"
                                        aria-labelledby="css-tab"
                                        hidden={activeEditor !== 'css'}
                                    >
                                        <Editor
                                            label="CSS source"
                                            language="css"
                                            value={css}
                                            onChange={setCss}
                                            dark={dark}
                                        />
                                    </div>
                                </div>
                                <div className="panel refs-panel">
                                    <PanelHeading
                                        title="Stylesheet references"
                                        detail="Applied in list order"
                                    />
                                    <div className="refs-content">
                                        {styleRefs.length === 0 && (
                                            <p className="empty-refs">
                                                Add external HTTPS stylesheets to your composition.
                                            </p>
                                        )}
                                        {styleRefs.map((ref, index) => (
                                            <div className="ref-item" key={index}>
                                                <div className="ref-row">
                                                    <span className="ref-number">
                                                        {String(index + 1).padStart(2, '0')}
                                                    </span>
                                                    <input
                                                        type="url"
                                                        aria-label={`Stylesheet URL ${index + 1}`}
                                                        placeholder="https://example.com/styles.css"
                                                        value={ref}
                                                        onChange={(event) =>
                                                            updateRef(index, event.target.value)
                                                        }
                                                        aria-invalid={
                                                            !!ref.trim() &&
                                                            !validStyleRef(ref.trim())
                                                        }
                                                    />
                                                    <button
                                                        className="small-button"
                                                        disabled={index === 0}
                                                        onClick={() => moveRef(index, -1)}
                                                        aria-label={`Move stylesheet ${index + 1} up`}
                                                    >
                                                        ↑
                                                    </button>
                                                    <button
                                                        className="small-button"
                                                        disabled={index === styleRefs.length - 1}
                                                        onClick={() => moveRef(index, 1)}
                                                        aria-label={`Move stylesheet ${index + 1} down`}
                                                    >
                                                        ↓
                                                    </button>
                                                    <button
                                                        className="small-button"
                                                        onClick={() =>
                                                            setStyleRefs((current) =>
                                                                current.filter(
                                                                    (_, i) => i !== index,
                                                                ),
                                                            )
                                                        }
                                                        aria-label={`Remove stylesheet ${index + 1}`}
                                                    >
                                                        ×
                                                    </button>
                                                </div>
                                                {!!ref.trim() && !validStyleRef(ref.trim()) && (
                                                    <p className="field-error">
                                                        Enter an HTTPS URL without credentials.
                                                    </p>
                                                )}
                                            </div>
                                        ))}
                                        <button
                                            className="text-button"
                                            onClick={() =>
                                                setStyleRefs((current) => [...current, ''])
                                            }
                                        >
                                            <Icon name="plus" />
                                            Add stylesheet
                                        </button>
                                    </div>
                                </div>
                            </div>
                            <div className="panel preview-panel" ref={composerPreview}>
                                <PanelHeading title="Live preview" badge="HTML" />
                                <Preview
                                    html={html}
                                    css={css}
                                    styleRefs={styleRefs.map((ref) => ref.trim())}
                                    imageWidth={widthValid ? width : 500}
                                />
                                <div className="panel-note">
                                    Updates as you type. Preview scrolls at the selected width.
                                    (Server-side sanitization may change the final SVG.)
                                </div>
                            </div>
                        </div>
                        <div className="composer-actions">
                            <button
                                className="primary-button"
                                disabled={composerBusy || !html.trim() || !widthValid || !refsValid}
                                onClick={generateImage}
                            >
                                {composerBusy ? 'Rendering…' : 'Render'}
                                <Icon name="arrow" />
                            </button>
                        </div>
                        {composerError && (
                            <div className="error-message" role="alert">
                                {composerError}
                            </div>
                        )}
                        {permalink && (
                            <ImageUrls
                                svgUrl={permalink}
                                label="Renered"
                                previousInput={inputIdentity !== generatedInput}
                                highlight
                            />
                        )}
                        {generatedImage && (
                            <div className="generated-result" key={generatedImage.revision}>
                                <div className="generated-viewer panel result-highlight">
                                    <PanelHeading title="Renered SVG" badge="SVG" />
                                    <p className="generated-status" role="status">
                                        {inputIdentity === generatedInput
                                            ? 'Your SVG is ready.'
                                            : 'Showing the previous SVG. Generate again to apply your changes.'}
                                    </p>
                                    <ZoomControl
                                        value={renderedZoom}
                                        onChange={setRenderedZoom}
                                        label="Rendered SVG zoom"
                                    />
                                    <div className="generated-scroll">
                                        <div
                                            className="generated-zoom-canvas"
                                            style={{
                                                width: (generatedImage.width * renderedZoom) / 100,
                                                height:
                                                    (generatedImage.height * renderedZoom) / 100,
                                            }}
                                        >
                                            <iframe
                                                title="Renered SVG"
                                                src={generatedImage.url}
                                                sandbox=""
                                                referrerPolicy="no-referrer"
                                                style={{
                                                    width: generatedImage.width,
                                                    height: generatedImage.height,
                                                    transform: `scale(${renderedZoom / 100})`,
                                                    transformOrigin: 'top left',
                                                }}
                                            />
                                        </div>
                                    </div>
                                </div>
                            </div>
                        )}
                    </section>
                    <section id="decode" className="workspace" aria-labelledby="decode-heading">
                        <div className="section-heading">
                            <div className="section-icon">
                                <Icon name="code" />
                            </div>
                            <div>
                                <h2 id="decode-heading">Decode UBCQ</h2>
                                <p>
                                    Restore HTML, CSS, stylesheets and canvas width from a UBCQ or
                                    image URL.
                                </p>
                            </div>
                        </div>
                        <form
                            className="panel decode-panel"
                            onSubmit={(event) => {
                                event.preventDefault();
                                void decodeAndEdit();
                            }}
                        >
                            <label htmlFor="decode-input">UBCQ or URL</label>
                            <textarea
                                id="decode-input"
                                rows={3}
                                value={decodeInput}
                                onChange={(event) => setDecodeInput(event.target.value)}
                            />
                            <div className="composer-actions">
                                <button
                                    className="primary-button"
                                    disabled={decodeBusy || composerBusy || !decodeInput.trim()}
                                >
                                    {decodeBusy ? 'Decoding…' : 'Decode and edit'}
                                    <Icon name="arrow" />
                                </button>
                            </div>
                            {decodeError && (
                                <div className="error-message" role="alert">
                                    {decodeError}
                                </div>
                            )}
                        </form>
                    </section>

                    <footer>
                        <span className="footer-brand">∑ PermaFormula</span>
                        <a href="/swagger-ui/index.html">
                            API documentation
                            <Icon name="external" />
                        </a>
                    </footer>
                </main>
            )}
        </>
    );
}
