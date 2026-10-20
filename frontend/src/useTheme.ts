import { useEffect, useState } from 'react';

export function useTheme() {
    const [themeChoice, setThemeChoice] = useState<'system' | 'light' | 'dark'>(() => {
        try {
            const saved = localStorage.getItem('permaformula-theme-choice');
            if (saved === 'light' || saved === 'dark') {
                return saved;
            }
        } catch {
            /* Storage is optional. */
        }
        return 'system';
    });
    const [dark, setDark] = useState(() =>
        themeChoice === 'system'
            ? window.matchMedia('(prefers-color-scheme: dark)').matches
            : themeChoice === 'dark',
    );
    useEffect(() => {
        const systemTheme = window.matchMedia('(prefers-color-scheme: dark)');
        const update = () =>
            setDark(themeChoice === 'system' ? systemTheme.matches : themeChoice === 'dark');
        update();
        systemTheme.addEventListener('change', update);
        try {
            if (themeChoice === 'system') {
                localStorage.removeItem('permaformula-theme-choice');
            } else {
                localStorage.setItem('permaformula-theme-choice', themeChoice);
            }
        } catch {
            /* Storage is optional. */
        }
        return () => systemTheme.removeEventListener('change', update);
    }, [themeChoice]);
    useEffect(() => {
        const root = document.documentElement;
        const theme = dark ? 'dark' : 'light';
        if (root.dataset.theme && root.dataset.theme !== theme) {
            root.classList.add('theme-transition');
            // Commit the starting colors before changing theme, even on a fast first click.
            void getComputedStyle(root).backgroundColor;
        }
        root.dataset.theme = theme;
        const timer = window.setTimeout(() => root.classList.remove('theme-transition'), 850);
        return () => {
            window.clearTimeout(timer);
            root.classList.remove('theme-transition');
        };
    }, [dark]);
    return { dark, setThemeChoice };
}
