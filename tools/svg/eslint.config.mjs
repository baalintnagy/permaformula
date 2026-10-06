import js from '@eslint/js';
import globals from 'globals';

export default [
  js.configs.recommended,
  { files: ['*.js'], languageOptions: { globals: globals.browser } },
  { files: ['*.mjs'], languageOptions: { globals: globals.node } },
  { rules: { eqeqeq: 'error', 'prefer-const': 'error', 'no-var': 'error' } },
];
