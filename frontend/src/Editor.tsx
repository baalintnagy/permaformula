import { useEffect, useRef, useState } from 'react';
import { ZoomControl } from './ZoomControl';
import { basicSetup } from 'codemirror';
import { EditorState, Compartment } from '@codemirror/state';
import { EditorView, keymap } from '@codemirror/view';
import { html } from '@codemirror/lang-html';
import { css } from '@codemirror/lang-css';
import { syntaxHighlighting } from '@codemirror/language';
import { sourceHighlightStyle } from './syntax-highlighting';
import { asciiMath } from './asciimath';
import { colorPickers } from './color-picker';

function editorTheme(dark: boolean, zoom: number) {
    return [
        EditorView.theme(
            {
                '&': {
                    backgroundColor: 'transparent',
                    color: dark ? '#e1e7f3' : '#172133',
                    fontSize: `${(13 * zoom) / 100}px`,
                },
                '.cm-content': {
                    fontFamily: '"Cascadia Code", "SFMono-Regular", Consolas, monospace',
                    padding: '16px 0',
                    minHeight: '100%',
                },
                '.cm-scroller': { overflow: 'auto' },
                '.cm-gutters': {
                    backgroundColor: 'transparent',
                    color: dark ? '#7e8ba5' : '#8b94a8',
                    border: 'none',
                },
                '.cm-activeLine, .cm-activeLineGutter': {
                    backgroundColor: dark ? '#ffffff06' : '#47556906',
                },
                '.cm-cursor': { borderLeftColor: dark ? '#9bbfea' : '#496f9f' },
                '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection': {
                    backgroundColor: dark ? '#496f9f70' : '#d5e3f5',
                },
                '.cm-tooltip': {
                    backgroundColor: dark ? '#242c3f' : '#fff',
                    border: '1px solid #8993a5',
                },
                '.cm-panels': { backgroundColor: dark ? '#242c3f' : '#f2f4f8', color: 'inherit' },
            },
            { dark },
        ),
        syntaxHighlighting(sourceHighlightStyle),
    ];
}

export function Editor({
    value,
    onChange,
    language,
    dark,
    label,
}: {
    value: string;
    onChange: (value: string) => void;
    language: 'html' | 'css' | 'asciimath';
    dark: boolean;
    label: string;
}) {
    const element = useRef<HTMLDivElement>(null);
    const [zoom, setZoom] = useState(100);
    const view = useRef<EditorView | null>(null);
    const callback = useRef(onChange);
    const theme = useRef(new Compartment());
    callback.current = onChange;

    useEffect(() => {
        const editor = new EditorView({
            parent: element.current!,
            state: EditorState.create({
                doc: value,
                extensions: [
                    basicSetup,
                    language === 'html' ? html() : language === 'css' ? css() : asciiMath,
                    language === 'css' ? colorPickers : [],
                    theme.current.of(editorTheme(dark, zoom)),
                    EditorView.lineWrapping,
                    EditorView.contentAttributes.of({
                        'aria-label': label,
                        'aria-multiline': 'true',
                        role: 'textbox',
                        spellcheck: 'false',
                    }),
                    keymap.of([
                        {
                            key: 'Escape',
                            run: (current) => {
                                current.contentDOM.blur();
                                return true;
                            },
                        },
                    ]),
                    EditorView.updateListener.of((update) => {
                        if (update.docChanged) {
                            callback.current(update.state.doc.toString());
                        }
                    }),
                ],
            }),
        });
        view.current = editor;
        return () => {
            editor.destroy();
            view.current = null;
        };
        // Each editor owns its document; external changes and theme updates are applied below.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [language, label]);

    useEffect(() => {
        const editor = view.current;
        if (editor && editor.state.doc.toString() !== value) {
            editor.dispatch({ changes: { from: 0, to: editor.state.doc.length, insert: value } });
        }
    }, [value]);
    useEffect(() => {
        view.current?.dispatch({ effects: theme.current.reconfigure(editorTheme(dark, zoom)) });
        view.current?.requestMeasure();
    }, [dark, zoom]);
    return (
        <>
            <ZoomControl
                value={zoom}
                onChange={setZoom}
                label={`${label} zoom`}
                className="editor-zoom"
            />
            <div className={`code-editor editor-${language}`} ref={element} />
        </>
    );
}
