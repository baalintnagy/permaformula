export function Icon({
    name,
}: {
    name: 'sun' | 'moon' | 'arrow' | 'copy' | 'check' | 'plus' | 'external' | 'formula' | 'code';
}) {
    const paths = {
        formula: <path d="M18 4H6l7 8-7 8h12" />,
        code: <path d="m8 6-6 6 6 6m8-12 6 6-6 6M14 4l-4 16" />,
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
