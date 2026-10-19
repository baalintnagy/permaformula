import { StrictMode, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Library } from './Library';
import { responseError } from './api';
import { SiteHeader } from './SiteHeader';
import { useTheme } from './useTheme';
import './styles.css';

function Admin() {
    const [username, setUsername] = useState('admin');
    const [password, setPassword] = useState('');
    const [token, setToken] = useState('');
    const [expiresAt, setExpiresAt] = useState(0);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    function expire() {
        setToken('');
        setError('Your login has expired. Sign in again.');
    }
    useEffect(() => {
        if (!token) {
            return;
        }
        const timer = window.setTimeout(expire, Math.max(0, expiresAt - Date.now()));
        return () => window.clearTimeout(timer);
    }, [token, expiresAt]);
    async function login() {
        if (busy) {
            return;
        }
        setBusy(true);
        setError('');
        try {
            const response = await fetch('/admin/login', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ username, password }),
            });
            if (!response.ok) {
                throw new Error(await responseError(response));
            }
            const result = await response.json();
            setPassword('');
            setExpiresAt(Date.now() + result.expiresIn * 1000);
            setToken(result.accessToken);
        } catch (failure) {
            setError(failure instanceof Error ? failure.message : 'Login failed.');
        } finally {
            setBusy(false);
        }
    }
    const { dark, setThemeChoice } = useTheme();
    return (
        <>
            <SiteHeader
                admin
                dark={dark}
                onToggleTheme={() => setThemeChoice(dark ? 'light' : 'dark')}
            >
                {token && (
                    <button
                        className="text-button"
                        onClick={() => {
                            setToken('');
                            setError('');
                        }}
                    >
                        Sign out
                    </button>
                )}
            </SiteHeader>
            {token ? (
                <Library admin token={token} onUnauthorized={expire} />
            ) : (
                <main>
                    <div className="intro">
                        <h1>
                            Admin <span>login</span>
                        </h1>
                        <p>Sign in to manage cached images.</p>
                    </div>
                    <form
                        className="panel refs-content admin-login"
                        onSubmit={(event) => {
                            event.preventDefault();
                            void login();
                        }}
                    >
                        <label>
                            Username{' '}
                            <input
                                autoComplete="username"
                                required
                                value={username}
                                onChange={(event) => setUsername(event.target.value)}
                            />
                        </label>
                        <label>
                            Password{' '}
                            <input
                                type="password"
                                autoComplete="current-password"
                                required
                                maxLength={72}
                                value={password}
                                onChange={(event) => setPassword(event.target.value)}
                            />
                        </label>
                        <button className="primary-button" disabled={busy}>
                            {busy ? 'Signing in…' : 'Sign in'}
                        </button>
                        {error && (
                            <div className="error-message" role="alert">
                                {error}
                            </div>
                        )}
                    </form>
                </main>
            )}
        </>
    );
}

createRoot(document.getElementById('root')!).render(
    <StrictMode>
        <Admin />
    </StrictMode>,
);
