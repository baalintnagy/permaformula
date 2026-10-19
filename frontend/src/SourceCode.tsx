import { useMemo, type ReactNode } from 'react';
import { html } from '@codemirror/lang-html';
import { css } from '@codemirror/lang-css';
import { highlightTree } from '@lezer/highlight';
import { sourceHighlightStyle } from './syntax-highlighting';

import { asciiMath } from './asciimath';

const languages = { html: html().language, css: css().language, formula: asciiMath };

export function SourceCode({
    value,
    language,
    zoom,
}: {
    value: string;
    language: 'html' | 'css' | 'formula';
    zoom: number;
}) {
    const label = language === 'formula' ? 'Expression source' : `${language.toUpperCase()} source`;
    const content = useMemo(() => {
        const parts: ReactNode[] = [];
        let position = 0;
        highlightTree(
            languages[language].parser.parse(value),
            sourceHighlightStyle,
            (from, to, className) => {
                if (from > position) {
                    parts.push(value.slice(position, from));
                }
                parts.push(
                    <span className={className} key={from}>
                        {value.slice(from, to)}
                    </span>,
                );
                position = to;
            },
        );
        if (position < value.length) {
            parts.push(value.slice(position));
        }
        return parts;
    }, [value, language]);
    return (
        <div className="library-code">
            <pre
                className="source-code"
                aria-label={label}
                style={{ fontSize: `${(13 * zoom) / 100}px` }}
            >
                <code>{content}</code>
            </pre>
        </div>
    );
}
