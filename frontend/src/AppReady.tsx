import { useEffect, type ReactNode } from 'react';

export function AppReady({ children }: { children: ReactNode }) {
    useEffect(() => {
        let frame = 0;
        const reveal = () => {
            frame = requestAnimationFrame(() => {
                document.documentElement.dataset.appState = 'ready';
                document.getElementById('app-loading')?.remove();
            });
        };
        if (document.readyState === 'complete') {
            reveal();
        } else {
            window.addEventListener('load', reveal, { once: true });
        }
        return () => {
            window.removeEventListener('load', reveal);
            cancelAnimationFrame(frame);
        };
    }, []);
    return children;
}
