// Shared native-shell tunnel configuration writer; contains no credentials.
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const [api, ui = '', readyFile = ''] = process.argv.slice(2);
const escape = (value) =>
    value.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
const launchPath = path.join(root, 'eclipse/PermaFormula API.launch');
let launch = fs.readFileSync(launchPath, 'utf8');
const block =
    /(<mapAttribute key="org\.eclipse\.debug\.core\.environmentVariables">)([\s\S]*?)(<\/mapAttribute>)/;
if (!block.test(launch)) {
    throw new Error('Eclipse environmentVariables block missing');
}
launch = launch.replace(block, (_, start, entries, end) => {
    for (const key of ['PERMAFORMULA_HOST', 'PERMAFORMULA_IMAGE_HOST']) {
        const entry = `<mapEntry key="${key}" value="${escape(api)}"/>`;
        const pattern = new RegExp(`<mapEntry\\s+key="${key}"\\s+value="[^"]*"\\s*/>`);
        entries = pattern.test(entries)
            ? entries.replace(pattern, entry)
            : `${entries}\n${entry}\n`;
    }
    return start + entries + end;
});
const envPath = path.join(root, 'frontend/.env.local');
const vscodePath = path.join(root, '.vscode/launch.json');
const vscode = JSON.parse(fs.readFileSync(vscodePath, 'utf8'));
const apiLaunch = vscode.configurations.find(
    (configuration) => configuration.name === 'PermaFormula API',
);
if (!apiLaunch) {
    throw new Error('VS Code PermaFormula API launch missing');
}
apiLaunch.env = {
    ...apiLaunch.env,
    PERMAFORMULA_HOST: api,
    PERMAFORMULA_IMAGE_HOST: api,
};
let env = fs.existsSync(envPath) ? fs.readFileSync(envPath, 'utf8') : '';
for (const [key, value] of Object.entries({ PERMAFORMULA_DEV_API: api, PERMAFORMULA_DEV_UI_ORIGIN: ui })) {
    const pattern = new RegExp(`^[ \\t]*${key}[ \\t]*=[^\\r\\n]*`, 'm');
    const line = `${key}=${value}`;
    env = pattern.test(env) ? env.replace(pattern, line) : `${env.trimEnd()}\n${line}\n`;
}
fs.writeFileSync(launchPath, launch);
fs.writeFileSync(vscodePath, JSON.stringify(vscode, null, 4) + '\n');
fs.writeFileSync(envPath, env);
if (readyFile) {
    fs.writeFileSync(readyFile + '.tmp', JSON.stringify({ ApiOrigin: api, UiOrigin: ui }));
    fs.renameSync(readyFile + '.tmp', readyFile);
}
