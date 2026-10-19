import { useId, useRef, useState, type ReactNode } from 'react';
import { Icon } from './Icon';

export function SiteHeader({
    dark,
    onToggleTheme,
    admin = false,
    children,
}: {
    dark: boolean;
    onToggleTheme: () => void;
    admin?: boolean;
    children?: ReactNode;
}) {
    const root = admin ? '/' : '';
    const [menuOpen, setMenuOpen] = useState(false);
    const navigationId = useId();
    const menuButton = useRef<HTMLButtonElement>(null);
    return (
        <header
            className={`site-header${menuOpen ? ' menu-open' : ''}`}
            onKeyDown={(event) => {
                if (event.key === 'Escape' && menuOpen) {
                    setMenuOpen(false);
                    menuButton.current?.focus();
                }
            }}
        >
            <a className="brand" href={`${root}#`}>
                <span className="brand-mark">
                    <Icon name="formula" />
                </span>
                <span>
                    PermaFormula<span className="brand-sub">BUILDER</span>
                </span>
            </a>
            <nav id={navigationId} aria-label="Workspaces" onClick={() => setMenuOpen(false)}>
                <a href={`${root}#formula`}>Simple expression</a>
                <a href={`${root}#composer`}>Formula + HTML + CSS</a>
                <a href={`${root}#decode`}>Decode UBCQ</a>
                <a href={`${root}#library`}>Library</a>
            </nav>
            <button
                className="theme-toggle"
                onClick={onToggleTheme}
                aria-label={`Switch to ${dark ? 'light' : 'dark'} theme`}
                title={`Switch to ${dark ? 'light' : 'dark'} theme`}
            >
                <Icon name={dark ? 'sun' : 'moon'} />
            </button>
            <button
                className="theme-toggle menu-toggle"
                ref={menuButton}
                aria-label={menuOpen ? 'Close navigation menu' : 'Open navigation menu'}
                aria-expanded={menuOpen}
                aria-controls={navigationId}
                onClick={() => setMenuOpen((open) => !open)}
            >
                <svg
                    width="20"
                    height="20"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="1.7"
                    strokeLinecap="round"
                    aria-hidden="true"
                >
                    <path d={menuOpen ? 'M6 6l12 12M6 18L18 6' : 'M4 6h16M4 12h16M4 18h16'} />
                </svg>
            </button>
            <div className="header-account">{children}</div>
        </header>
    );
}
