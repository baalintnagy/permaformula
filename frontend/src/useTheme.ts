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
        document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    }, [dark]);
    return { dark, setThemeChoice };
}
