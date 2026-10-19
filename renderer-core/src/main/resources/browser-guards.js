// Generated from tools/svg/browser-guards.js; run npm run build. Do not edit.
(() => {
  // browser-guards.js
  function blocked() {
    throw new DOMException("This API is disabled in the renderer", "SecurityError");
  }
  for (const name of [
    "Worker",
    "SharedWorker",
    "RTCPeerConnection",
    "webkitRTCPeerConnection",
    "WebTransport",
    "WebSocketStream"
  ]) {
    Object.defineProperty(globalThis, name, {
      value: blocked,
      writable: false,
      configurable: false
    });
  }
  Object.defineProperty(globalThis, "open", {
    value: () => null,
    writable: false,
    configurable: false
  });
  if ("serviceWorker" in navigator) {
    const deniedRegistration = {
      value: () => Promise.reject(
        new DOMException("Service workers are disabled in the renderer", "SecurityError")
      ),
      writable: false,
      configurable: false
    };
    Object.defineProperty(ServiceWorkerContainer.prototype, "register", deniedRegistration);
    Object.defineProperty(navigator.serviceWorker, "register", deniedRegistration);
  }
})();
