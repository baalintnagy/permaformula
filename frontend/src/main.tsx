import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { AppReady } from './AppReady';
import './styles.css';

createRoot(document.getElementById('root')!).render(
    <StrictMode>
        <AppReady>
            <App />
        </AppReady>
    </StrictMode>,
);
