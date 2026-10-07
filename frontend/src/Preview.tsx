import { useEffect, useMemo, useRef, useState } from 'react';
import { previewDocument } from './preview-document';

export function Preview({
  formula,
  html,
  css,
  styleRefs,
  imageWidth,
}: {
  formula?: string;
  html?: string;
  css?: string;
  styleRefs?: string[];
  imageWidth?: number;
}) {
  const frame = useRef<HTMLIFrameElement>(null);
  const [source, setSource] = useState('');
  const [zoom, setZoom] = useState(100);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error'>('loading');
  const refsIdentity = JSON.stringify(styleRefs || []);
  const document = useMemo(
    () => previewDocument({ formula, html, css, styleRefs: JSON.parse(refsIdentity), imageWidth }),
    [formula, html, css, refsIdentity, imageWidth],
  );

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setStatus('loading');
      setSource(document);
    }, 200);
    return () => window.clearTimeout(timer);
  }, [document]);
  useEffect(() => {
    function onMessage(event: MessageEvent) {
      if (
        event.source === frame.current?.contentWindow &&
        event.data?.type === 'permmath-preview'
      ) {
        setStatus(event.data.ok ? 'ready' : 'error');
      }
    }
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, []);
  useEffect(() => {
    if (formula !== undefined && status === 'ready') {
      frame.current?.contentWindow?.postMessage({ type: 'permmath-zoom', zoom }, '*');
    }
  }, [zoom, status, formula]);
  useEffect(() => {
    if (!source || status !== 'loading') return;
    const timer = window.setTimeout(() => setStatus('error'), 15000);
    return () => window.clearTimeout(timer);
  }, [source, status]);

  return (
    <div
      className={`preview-container ${formula === undefined ? 'html-preview' : 'formula-preview'}`}
    >
      <div className="preview-status" role="status">
        {status === 'loading'
          ? 'Updating preview…'
          : status === 'error'
            ? 'Preview unavailable. Check the input or use server rendering.'
            : 'Preview up to date'}
      </div>
      <div className="preview-scroll">
        <iframe
          ref={frame}
          title={formula === undefined ? 'HTML live preview' : 'Formula live preview'}
          sandbox="allow-scripts"
          referrerPolicy="no-referrer"
          srcDoc={source}
          style={formula === undefined ? { width: imageWidth || 500 } : undefined}
        />
      </div>
      {formula !== undefined && (
        <label className="formula-zoom">
          Zoom
          <input
            type="range"
            min="50"
            max="250"
            step="10"
            value={zoom}
            aria-label="Formula preview zoom"
            onChange={(event) => setZoom(Number(event.target.value))}
          />
          <output>{zoom}%</output>
        </label>
      )}
    </div>
  );
}
