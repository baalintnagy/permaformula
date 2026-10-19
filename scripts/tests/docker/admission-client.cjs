// Disposable Docker client for distinct source addresses. No production use.
const tls = require('node:tls');
const net = require('node:net');
const { readFileSync } = require('node:fs');
const [mode, countText] = process.argv.slice(2),
    count = Number(countText);
const ca = readFileSync('/fixture/issued-root.pem'),
    sockets = [];
const path = '/ubcq?formula=' + 'x'.repeat((mode === 'raw' ? 17 : 19) * 1048576);
let responses = 0,
    errors = 0,
    lastError,
    badStatuses = 0;
function failed(error) {
    errors++;
    lastError = error.message;
}
for (let i = 0; i < count; i++) {
    const socket =
        mode === 'redirect'
            ? net.connect({ host: 'nginx.test', port: 8080 })
            : tls.connect({ host: 'nginx.test', port: 8443, servername: 'nginx.test', ca });
    sockets.push(socket);
    socket.on('error', failed);
    if (mode === 'raw') {
        socket.on('data', () => {});
    } else {
        socket.once('readable', () => {
            const head = socket.read(32);
            if (
                head &&
                head.toString().startsWith(mode === 'redirect' ? 'HTTP/1.1 308' : 'HTTP/1.1 200')
            ) {
                responses++;
            }
        });
    }
    socket.on(mode === 'redirect' ? 'connect' : 'secureConnect', () =>
        socket.write(
            'GET ' +
                path +
                (mode !== 'raw'
                    ? ' HTTP/1.1\r\nHost: nginx.test\r\nConnection: close\r\n\r\n'
                    : ''),
        ),
    );
}
setInterval(
    () =>
        console.log(
            JSON.stringify({
                open: sockets.filter((s) => !s.destroyed).length,
                responses,
                badStatuses,
                errors,
                lastError,
            }),
        ),
    500,
);
setTimeout(() => {
    for (const socket of sockets) {
        socket.destroy();
    }
    process.exit(0);
}, 13000);
