// Shared, dependency-free helpers for the Bash-invoked Docker regressions.
const assert = require('node:assert/strict');
const http = require('node:http');
const https = require('node:https');
const { spawn, spawnSync } = require('node:child_process');
const { resolve } = require('node:path');
const { randomBytes } = require('node:crypto');
const root = resolve(__dirname, '../../..');
const alpine =
    'alpine:3.23@sha256:85fe1e81d6758c208f3e1eed4338a1997e19d4be002d4dd32d3100c9a8c010a0';
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
function docker(args, { allowFailure = false } = {}) {
    const result = spawnSync('docker', args, {
        cwd: root,
        encoding: 'utf8',
        windowsHide: true,
        maxBuffer: 32 * 1024 * 1024,
    });
    if (result.error) {
        throw result.error;
    }
    if (result.status !== 0 && !allowFailure) {
        throw new Error(`docker ${args[0]} failed: ${result.stderr || result.stdout}`);
    }
    return { status: result.status, stdout: result.stdout.trim(), stderr: result.stderr.trim() };
}
const inspect = (name) => JSON.parse(docker(['inspect', name]).stdout)[0];
function request(url, headers = {}, method = 'GET', timeout = 65000) {
    return new Promise((resolve, reject) => {
        const transport = new URL(url).protocol === 'https:' ? https : http;
        const req = transport.request(url, { method, headers, agent: false }, (res) => {
            const parts = [];
            let size = 0;
            res.on('data', (part) => {
                size += part.length;
                if (size > 32 * 1024 * 1024) {
                    res.destroy(new Error('Fixture response exceeds 32 MiB'));
                } else {
                    parts.push(part);
                }
            });
            res.on('error', reject);
            res.on('end', () => {
                const body = Buffer.concat(parts);
                resolve({
                    status: res.statusCode,
                    headers: res.headers,
                    body,
                    text: body.toString('utf8'),
                });
            });
        });
        req.on('error', reject);
        req.setTimeout(timeout, () => req.destroy(new Error('Fixture request timed out')));
        req.end();
    });
}
function status(response, expected) {
    assert.equal(response.status, expected, response.text.slice(0, 300));
}
async function follow(url, headers = {}) {
    for (let redirects = 0; redirects <= 5; redirects++) {
        const response = await request(url, headers);
        if (![301, 302, 303, 307, 308].includes(response.status) || !response.headers.location) {
            return response;
        }
        url = new URL(response.headers.location, url).href;
    }
    throw new Error('Fixture redirect limit exceeded');
}
function svg(response) {
    status(response, 200);
    assert.match(response.headers['content-type'] || '', /^image\/svg\+xml/);
    assert.match(response.text, /<svg/);
    assert.match(response.headers['content-security-policy'] || '', /sandbox/);
    imageHeaders(response);
}
function imageHeaders(response) {
    assert.equal(response.headers['x-content-type-options'], 'nosniff');
    assert.equal(response.headers['referrer-policy'], 'no-referrer');
    assert.equal(response.headers['set-cookie'], undefined);
    assert.equal(response.headers['access-control-allow-credentials'], undefined);
}
function jpeg(response) {
    status(response, 200);
    assert.match(response.headers['content-type'] || '', /^image\/jpeg/);
    assert(
        response.body.length >= 4 && response.body[0] === 255 && response.body[1] === 216,
        'Expected JPEG',
    );
    imageHeaders(response);
}
function project(name) {
    assert.match(name, /^[a-z0-9][a-z0-9_-]*$/, 'Invalid Compose project name');
    return name;
}
async function ready(url) {
    for (let attempt = 0; attempt < 40; attempt++) {
        try {
            return await request(url, {}, 'GET', 3000);
        } catch {
            await delay(250);
        }
    }
    throw new Error(`Fixture did not listen: ${url}`);
}
class Fixture {
    constructor(prefix) {
        this.name = `${prefix}-${randomBytes(5).toString('hex')}`;
        this.containers = [];
        this.networks = [];
    }
    network(name = this.name) {
        docker(['network', 'create', name]);
        this.networks.push(name);
        return name;
    }
    container(name, args) {
        docker(['run', '-d', '--name', name, ...args]);
        this.containers.push(name);
        return name;
    }
    cleanup() {
        for (const name of this.containers.reverse()) {
            docker(['rm', '-f', name], { allowFailure: true });
        }
        for (const name of this.networks.reverse()) {
            docker(['network', 'rm', name], { allowFailure: true });
        }
    }
}
function options(args, defaults, flags = []) {
    const result = { ...defaults };
    for (let i = 0; i < args.length; i++) {
        if (args[i] === '--help') {
            return { help: true };
        }
        const key = args[i].replace(/^--/, '');
        assert(
            args[i].startsWith('--') && Object.hasOwn(defaults, key),
            `Unknown option: ${args[i]}`,
        );
        if (flags.includes(key)) {
            result[key] = true;
        } else {
            assert(args[i + 1] && !args[i + 1].startsWith('--'), `Missing value: ${args[i]}`);
            result[key] = args[++i];
        }
    }
    return result;
}
function cli(main, defaults, usage, flags = []) {
    Promise.resolve()
        .then(() => {
            const args = options(process.argv.slice(2), defaults, flags);
            if (args.help) {
                console.log(usage);
            } else {
                return main(args);
            }
        })
        .catch((error) => {
            console.error(error.stack || error.message);
            process.exitCode = 1;
        });
}
function dockerAsync(args, timeout = 60000) {
    let child;
    const promise = new Promise((resolve, reject) => {
        child = spawn('docker', args, { cwd: root, windowsHide: true });
        let stdout = '',
            stderr = '';
        const timer = setTimeout(() => {
            child.kill();
            reject(new Error('Concurrent render exceeded its deadline'));
        }, timeout);
        child.stdout.on('data', (part) => {
            stdout += part;
        });
        child.stderr.on('data', (part) => {
            stderr += part;
        });
        child.on('error', (error) => {
            clearTimeout(timer);
            reject(error);
        });
        child.on('exit', (code) => {
            clearTimeout(timer);
            code === 0 ? resolve(stdout) : reject(new Error(stderr || stdout));
        });
    });
    return { promise, kill: () => child?.kill() };
}
module.exports = {
    assert,
    root,
    alpine,
    delay,
    docker,
    dockerAsync,
    inspect,
    request,
    follow,
    status,
    svg,
    jpeg,
    imageHeaders,
    project,
    ready,
    Fixture,
    options,
    cli,
};
