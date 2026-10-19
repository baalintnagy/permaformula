const { resolve } = require('node:path');

process.env.PLAYWRIGHT_BROWSERS_PATH ||= resolve(__dirname, '../.runtime/frontend-browsers');
module.exports = require('../frontend/node_modules/playwright');
