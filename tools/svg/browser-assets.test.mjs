import assert from 'node:assert/strict';
import test from 'node:test';
import { waitForAssets } from './browser-assets.js';

class ImageStub extends EventTarget {
  complete = false;
  naturalWidth = 0;
  src = 'https://example.test/image.png';
  currentSrc = 'https://example.test/selected.png';
  listeners = new Set();

  addEventListener(type, listener, options) {
    this.listeners.add(listener);
    super.addEventListener(type, listener, options);
  }

  removeEventListener(type, listener) {
    this.listeners.delete(listener);
    super.removeEventListener(type, listener);
  }
}

const documentWith = (images, fonts = Promise.resolve()) => ({ images, fonts: { ready: fonts } });

test('waits for both fonts and pending images, then releases image listeners', async () => {
  const image = new ImageStub();
  const fonts = Promise.withResolvers();
  let ready = false;
  const waiting = waitForAssets(documentWith([image], fonts.promise)).then(() => {
    ready = true;
  });
  image.dispatchEvent(new Event('load'));
  await Promise.resolve();
  assert.equal(ready, false);
  assert.equal(image.listeners.size, 0);
  fonts.resolve();
  await waiting;
  assert.equal(ready, true);
});

test('rejects failed pending images with their selected URL and releases listeners', async () => {
  const image = new ImageStub();
  const waiting = waitForAssets(documentWith([image]));
  image.dispatchEvent(new Event('error'));
  await assert.rejects(waiting, { message: `Image failed: ${image.currentSrc}` });
  assert.equal(image.listeners.size, 0);
});

test('accepts cached images and rejects completed broken images', async () => {
  const image = new ImageStub();
  image.complete = true;
  image.naturalWidth = 100;
  await waitForAssets(documentWith([image]));
  assert.equal(image.listeners.size, 0);
  image.naturalWidth = 0;
  image.currentSrc = '';
  await assert.rejects(waitForAssets(documentWith([image])), {
    message: `Image failed: ${image.src}`,
  });
});
