// Service Worker for Structured Data Journal
// Cache version updated at build time: {ts}
const CACHE_NAME = 'structured-data-journal-v{ts}';
const ASSETS_TO_CACHE = [
  './',
  './index.html',
  './style.css?ts={ts}',
  './js/main.js?ts={ts}',
  './manifest.json',
  './icon-192.png',
  './icon-512.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(ASSETS_TO_CACHE);
    }).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((cacheNames) => {
      return Promise.all(
        cacheNames
          .filter((name) => name.startsWith('structured-data-journal-') && name !== CACHE_NAME)
          .map((name) => caches.delete(name))
      );
    }).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  const url = new URL(req.url);

  // Never cache API/sync calls or non-GET requests
  if (req.method !== 'GET' || url.pathname.startsWith('/journal/api')) {
    return;
  }

  // Network-first strategy with cache fallback for navigation / app shell
  event.respondWith(
    fetch(req)
      .then((networkResponse) => {
        // Only cache valid basic responses
        if (networkResponse && networkResponse.status === 200 && networkResponse.type === 'basic') {
          const responseToCache = networkResponse.clone();
          caches.open(CACHE_NAME).then((cache) => {
            cache.put(req, responseToCache);
          });
        }
        return networkResponse;
      })
      .catch(() => {
        // Fallback to cache when offline
        return caches.match(req).then((cachedResponse) => {
          if (cachedResponse) {
            return cachedResponse;
          }
          if (req.mode === 'navigate') {
            return caches.match('./index.html')
              .then((resp) => resp || caches.match('./'));
          }
        });
      })
  );
});
