import js from '@eslint/js';
import globals from 'globals';

export default [
    js.configs.recommended,
    { files: ['*.js'], languageOptions: { globals: globals.browser } },
    { files: ['*.mjs'], languageOptions: { globals: globals.node } },
    {
        rules: {
            eqeqeq: 'error',
            curly: ['error', 'all'],
            'prefer-const': 'error',
            'no-var': 'error',
            'prefer-template': 'error',
            'no-implicit-coercion': 'error',
        },
    },
];
