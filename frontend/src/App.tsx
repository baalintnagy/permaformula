import { useEffect, useId, useRef, useState } from 'react';
import { Editor } from './Editor';
import { Preview } from './Preview';
import { createPermalink, formulaUrl, responseError, validStyleRef } from './api';

const initialFormula = 'sum_(i=1)^n i^3 = ((n(n+1))/2)^2';
const initialHtml = `<div class="math-card">
  <span class="eyebrow">A LITTLE MATHEMATICAL BEAUTY</span>
  <h1>The sum of cubes</h1>
  <p>A familiar series. An elegant identity.</p>
  <div class="equation">
    \`sum_(i=1)^n i^3 = ((n(n+1))/2)^2\`
  </div>
  <p class="caption">The sum of the first n cubes equals
    the square of the nth triangular number.</p>
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

function Icon({
  name,
}: {
  name: 'sun' | 'moon' | 'arrow' | 'copy' | 'check' | 'plus' | 'external';
}) {
  const paths = {
    sun: (
      <>
        <circle cx="12" cy="12" r="4" />
        <path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5" />
      </>
    ),
    moon: <path d="M20.5 13A8.5 8.5 0 0 1 11 3.5 8.5 8.5 0 1 0 20.5 13Z" />,
    arrow: <path d="M5 12h14m-6-6 6 6-6 6" />,
    copy: (
      <>
        <rect x="8" y="8" width="12" height="12" rx="2" />
        <path d="M16 8V4H4v12h4" />
      </>
    ),
    check: <path d="m5 12 4 4L19 6" />,
    plus: <path d="M12 5v14M5 12h14" />,
    external: (
      <>
        <path d="M14 3h7v7m0-7L10 14" />
        <path d="M10 3H3v18h18v-7" />
      </>
    ),
  };
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {paths[name]}
    </svg>
  );
}

function CopyUrl({ url, label }: { url: string; label: string }) {
  const id = useId();
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState(false);
  const input = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    const field = input.current;
    if (!field) return;
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
    if (!copied) return;
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
        {error ? 'Select and copy the URL manually.' : copied ? 'URL copied to clipboard.' : ''}
      </span>
    </div>
  );
}

function PanelHeading({
  title,
  detail,
  badge,
}: {
  title: string;
  detail?: string;
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
  const [dark, setDark] = useState(document.documentElement.dataset.theme === 'dark');
  const [formula, setFormula] = useState(initialFormula);
  const [html, setHtml] = useState(initialHtml);
  const [css, setCss] = useState(initialCss);
  const [styleRefs, setStyleRefs] = useState<string[]>([]);
  const [imageWidth, setImageWidth] = useState('800');
  const [activeEditor, setActiveEditor] = useState<'html' | 'css'>('html');
  const [image, setImage] = useState<{ url: string; formula: string } | null>(null);
  const [formulaBusy, setFormulaBusy] = useState(false);
  const [formulaError, setFormulaError] = useState('');
  const [permalink, setPermalink] = useState('');
  const [generatedInput, setGeneratedInput] = useState('');
  const [composerBusy, setComposerBusy] = useState(false);
  const [composerError, setComposerError] = useState('');
  const [openFallback, setOpenFallback] = useState(false);
  const imageObject = useRef('');
  const formulaAbort = useRef<AbortController | null>(null);
  const composerAbort = useRef<AbortController | null>(null);
  const formulaLock = useRef(false);
  const composerLock = useRef(false);
  const width = Number(imageWidth);
  const widthValid = Number.isInteger(width) && width >= 220 && width <= 4000;
  const refsValid = styleRefs.every((ref) => !ref.trim() || validStyleRef(ref.trim()));
  const inputIdentity = JSON.stringify([html, css, styleRefs, imageWidth]);
  const imgUrl = formulaUrl(formula);

  useEffect(() => {
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    try {
      localStorage.setItem('permmath-theme', dark ? 'dark' : 'light');
    } catch {
      /* Storage is optional. */
    }
  }, [dark]);
  useEffect(
    () => () => {
      formulaAbort.current?.abort();
      composerAbort.current?.abort();
      if (imageObject.current) URL.revokeObjectURL(imageObject.current);
    },
    [],
  );

  async function renderFormula() {
    if (formulaLock.current) return;
    formulaLock.current = true;
    setFormulaBusy(true);
    setFormulaError('');
    const controller = new AbortController();
    formulaAbort.current = controller;
    const submittedFormula = formula;
    try {
      const response = await fetch(formulaUrl(submittedFormula, window.location.origin), {
        signal: controller.signal,
        credentials: 'omit',
        headers: { Accept: 'image/svg+xml' },
      });
      if (!response.ok) throw new Error(await responseError(response));
      if (!response.headers.get('content-type')?.includes('image/svg+xml'))
        throw new Error('The server did not return an SVG image.');
      const blob = await response.blob();
      if (controller.signal.aborted) return;
      const url = URL.createObjectURL(blob);
      if (imageObject.current) URL.revokeObjectURL(imageObject.current);
      imageObject.current = url;
      setImage({ url, formula: submittedFormula });
    } catch (error) {
      if (!controller.signal.aborted)
        setFormulaError(error instanceof Error ? error.message : 'Unable to render the image.');
    } finally {
      formulaLock.current = false;
      setFormulaBusy(false);
    }
  }

  async function generateImage() {
    if (composerLock.current) return;
    composerLock.current = true;
    // Reserve the tab during the user gesture, before the asynchronous API call.
    const tab = window.open('about:blank', '_blank');
    if (tab) {
      tab.opener = null;
      tab.document.title = 'PermMath — Generating image';
      tab.document.body.textContent = 'Generating your image…';
      // Request a background tab while the user gesture is still active.
      // Browsers ultimately decide whether scripted focus changes are allowed.
      tab.blur();
      window.focus();
    }
    setComposerBusy(true);
    setComposerError('');
    setOpenFallback(false);
    const controller = new AbortController();
    composerAbort.current = controller;
    const submittedInput = inputIdentity;
    try {
      const url = await createPermalink(
        { html, css, styleRefs, imageWidth: width },
        controller.signal,
      );
      if (controller.signal.aborted) {
        tab?.close();
        return;
      }
      setPermalink(url);
      setGeneratedInput(submittedInput);
      if (tab && !tab.closed) tab.location.replace(url);
      else setOpenFallback(true);
    } catch (error) {
      tab?.close();
      if (!controller.signal.aborted)
        setComposerError(
          error instanceof Error ? error.message : 'Unable to generate the permalink.',
        );
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
      <header className="site-header">
        <a className="brand" href="#">
          <span className="brand-mark">∑</span>
          <span>
            PermMath<span className="brand-sub">STUDIO</span>
          </span>
        </a>
        <nav aria-label="Workspaces">
          <a href="#formula">Formula</a>
          <a href="#composer">HTML composer</a>
        </nav>
        <button
          className="theme-toggle"
          onClick={() => setDark(!dark)}
          aria-label={`Switch to ${dark ? 'light' : 'dark'} theme`}
          title={`Switch to ${dark ? 'light' : 'dark'} theme`}
        >
          <Icon name={dark ? 'sun' : 'moon'} />
        </button>
      </header>
      <main>
        <div className="intro">
          <span className="eyebrow">FROM EXPRESSION TO IMAGE</span>
          <h1>
            Give your ideas <span>a clear form.</span>
          </h1>
          <p>Write math. Style HTML. Create an image you can share anywhere.</p>
          <div className="intro-meta">
            <span className="live-dot" /> Live local previews{' '}
            <span className="meta-divider">/</span> SVG output
          </div>
        </div>

        <section id="formula" className="workspace" aria-labelledby="formula-heading">
          <div className="section-heading">
            <div className="section-number">01</div>
            <div>
              <h2 id="formula-heading">Formula playground</h2>
              <p>A single expression, beautifully rendered.</p>
            </div>
            <span className="section-tag">ASCIIMATH → SVG</span>
          </div>
          <div className="workspace-grid formula-grid">
            <div className="panel">
              <PanelHeading title="Expression" detail="ASCIIMath" badge="SOURCE" />
              <Editor
                label="ASCIIMath formula"
                language="asciimath"
                value={formula}
                onChange={setFormula}
                dark={dark}
              />
              <div className="editor-hint">
                Try <code>sqrt(x^2 + y^2)</code> or <code>int_0^1 x^2 dx</code>
                <span>Esc to leave editor</span>
              </div>
            </div>
            <div className="panel preview-panel">
              <PanelHeading title="Live preview" badge="LOCAL" />
              <Preview formula={formula} />
              <div className="panel-note">Updates as you type. No server request.</div>
            </div>
          </div>
          <div className="action-panel">
            <CopyUrl url={imgUrl} label="Formula image URL" />
            <button
              className="primary-button"
              disabled={!formula.trim() || formulaBusy}
              onClick={renderFormula}
            >
              <span>{formulaBusy ? 'Rendering…' : 'Render image'}</span>
              <Icon name="arrow" />
            </button>
          </div>
          {formulaError && (
            <div className="error-message" role="alert">
              {formulaError}
            </div>
          )}
          {image && (
            <div className="server-result panel">
              <PanelHeading
                title="Server image"
                badge={image.formula !== formula ? 'PREVIOUS INPUT' : 'SVG'}
              />
              <div className="result-canvas">
                <img
                  src={image.url}
                  alt={`Rendered formula: ${image.formula}`}
                  onError={() => setFormulaError('The returned SVG could not be displayed.')}
                />
              </div>
            </div>
          )}
        </section>

        <section id="composer" className="workspace" aria-labelledby="composer-heading">
          <div className="section-heading">
            <div className="section-number">02</div>
            <div>
              <h2 id="composer-heading">HTML image composer</h2>
              <p>Bring layout, typography, and formulas together.</p>
            </div>
            <span className="section-tag">HTML + CSS → SVG</span>
          </div>
          <div className="workspace-grid composer-grid">
            <div className="composer-editors">
              <div className="panel">
                <div className="editor-tabs" role="tablist" aria-label="Source language">
                  <button
                    id="html-tab"
                    role="tab"
                    aria-selected={activeEditor === 'html'}
                    aria-controls="html-editor-panel"
                    tabIndex={activeEditor === 'html' ? 0 : -1}
                    onKeyDown={(event) => {
                      if (['ArrowLeft', 'ArrowRight'].includes(event.key)) {
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
                      if (['ArrowLeft', 'ArrowRight'].includes(event.key)) {
                        setActiveEditor('html');
                        document.getElementById('html-tab')?.focus();
                      }
                    }}
                    onClick={() => setActiveEditor('css')}
                  >
                    CSS<span>.css</span>
                  </button>
                  <span className="tabs-caption">SOURCE</span>
                </div>
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
                <div className="editor-hint">
                  Wrap ASCIIMath in backticks: <code>`sqrt(x)`</code>
                  <span>Esc to leave editor</span>
                </div>
              </div>
              <div className="panel refs-panel">
                <PanelHeading title="Stylesheet references" detail="Applied in list order" />
                <div className="refs-content">
                  {styleRefs.length === 0 && (
                    <p className="empty-refs">
                      Add external HTTPS stylesheets to your composition.
                    </p>
                  )}
                  {styleRefs.map((ref, index) => (
                    <div className="ref-item" key={index}>
                      <div className="ref-row">
                        <span className="ref-number">{String(index + 1).padStart(2, '0')}</span>
                        <input
                          type="url"
                          aria-label={`Stylesheet URL ${index + 1}`}
                          placeholder="https://example.com/styles.css"
                          value={ref}
                          onChange={(event) => updateRef(index, event.target.value)}
                          aria-invalid={!!ref.trim() && !validStyleRef(ref.trim())}
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
                            setStyleRefs((current) => current.filter((_, i) => i !== index))
                          }
                          aria-label={`Remove stylesheet ${index + 1}`}
                        >
                          ×
                        </button>
                      </div>
                      {!!ref.trim() && !validStyleRef(ref.trim()) && (
                        <p className="field-error">Enter an HTTPS URL without credentials.</p>
                      )}
                    </div>
                  ))}
                  <button
                    className="text-button"
                    onClick={() => setStyleRefs((current) => [...current, ''])}
                  >
                    <Icon name="plus" />
                    Add stylesheet
                  </button>
                </div>
              </div>
            </div>
            <div className="panel preview-panel">
              <PanelHeading title="Live preview" badge="LOCAL" />
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
                      onChange={(event) => setImageWidth(event.target.value)}
                      aria-invalid={!widthValid}
                    />
                    <span>px</span>
                  </div>
                </label>
                <span>White canvas</span>
              </div>
              {!widthValid && (
                <p className="field-error width-error">
                  Use a whole number from 220 to 4000 pixels.
                </p>
              )}
              <Preview
                html={html}
                css={css}
                styleRefs={styleRefs.map((ref) => ref.trim())}
                imageWidth={widthValid ? width : 800}
              />
              <div className="panel-note">
                Preview scrolls at the selected width. Server sanitization may change the final
                image.
              </div>
            </div>
          </div>
          <div className="composer-actions">
            <p>
              <span className="live-dot" />
              Ready when you are.
              <small>Generate a permalink and open the server image in a new tab.</small>
            </p>
            <button
              className="primary-button"
              disabled={composerBusy || !html.trim() || !widthValid || !refsValid}
              onClick={generateImage}
            >
              {composerBusy ? 'Generating…' : 'Generate & open image'}
              <Icon name="external" />
            </button>
          </div>
          {composerError && (
            <div className="error-message" role="alert">
              {composerError}
            </div>
          )}
          {permalink && (
            <div className="permalink-result panel">
              <CopyUrl
                url={permalink}
                label={
                  inputIdentity === generatedInput
                    ? 'Generated image permalink'
                    : 'Generated image permalink (previous input)'
                }
              />
              <a href={permalink} target="_blank" rel="noopener noreferrer" className="text-button">
                Open image
                <Icon name="external" />
              </a>
              {openFallback && (
                <p role="status">
                  Your browser blocked the new tab. Use “Open image” to view the result.
                </p>
              )}
            </div>
          )}
        </section>
        <footer>
          <span className="footer-brand">∑ PermMath</span>
          <p>Made for expressions worth sharing.</p>
          <a href="/swagger-ui/index.html">
            API documentation
            <Icon name="external" />
          </a>
        </footer>
      </main>
    </>
  );
}
