export async function waitForAssets(document) {
    await Promise.all([document.fonts.ready, ...Array.from(document.images, waitForImage)]);
}

function waitForImage(image) {
    if (image.complete) {
        return image.naturalWidth ? Promise.resolve() : Promise.reject(imageError(image));
    }
    return new Promise((resolve, reject) => {
        const cleanup = () => {
            image.removeEventListener('load', onLoad);
            image.removeEventListener('error', onError);
        };
        const onLoad = () => {
            cleanup();
            resolve();
        };
        const onError = () => {
            cleanup();
            reject(imageError(image));
        };
        image.addEventListener('load', onLoad, { once: true });
        image.addEventListener('error', onError, { once: true });
    });
}

function imageError(image) {
    return new Error(`Image failed: ${image.currentSrc || image.src}`);
}
