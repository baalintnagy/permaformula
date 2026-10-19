export function ZoomControl({
    value,
    onChange,
    label,
    className = '',
}: {
    value: number;
    onChange: (value: number) => void;
    label: string;
    className?: string;
}) {
    return (
        <label className={`zoom-control ${className}`}>
            <span>Zoom</span>
            <input
                type="range"
                min="10"
                max="1000"
                step="10"
                value={value}
                aria-label={label}
                onChange={(event) => onChange(Number(event.target.value))}
            />
            <output>{value}%</output>
        </label>
    );
}
