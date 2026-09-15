# Structured Data Journal - Web Frontend

Mobile-friendly Single Page Application (SPA) for recording and analyzing
structured journal data, built with ClojureScript, Helix (React 18),
shadow-cljs, and the Small Clojure Interpreter (SCI).

## Features

- **Offline-First Storage**: Operates offline using browser LocalStorage
  with background synchronization when connectivity is available.
- **Smart Entry Input**:
  - Native HTML5 `datetime-local` picker for entry timestamps.
  - Multiline `textarea` for journal notes and descriptions.
  - Dynamic key-value pairs (numbers, free text, tags).
  - Native `<datalist>` autocomplete suggestions for both keys and values.
  - Unified suggestion chips blending recent and frequent keywords.
  - Automatically saves pending typed key-value pairs on entry save.
- **SCI Code Dashboards**:
  - Persistent custom dashboard running user Clojure code in the browser.
  - Pre-packaged starter scripts: counts by key, averages & stddev,
    entry intervals, 7-day windows, and visual bar charts.
- **Cache Busting**: Shadow-cljs build hooks automatically inject unique
  build timestamps into `public/index.html` and compile `version.cljs`.

## Getting Started

### 1. Install Dependencies

```bash
npm install
# or
make install
```

### 2. Run Development Server

Start shadow-cljs with live code reload:

```bash
npx shadow-cljs watch frontend
# or
make dev
```

Open your browser to:
`http://localhost:3000`

The dev server automatically proxies backend `/storage/` API requests to
`http://localhost:8000`.

### 3. Running Tests

Run the test suite in Node:

```bash
npx shadow-cljs compile test && node out/node-tests.js
# or
make test
```

Watch and re-run tests on file change:

```bash
npx shadow-cljs watch frontend test
# or
make dev-test
```

### 4. Production Release Build

Create an optimized release bundle:

```bash
npx shadow-cljs release frontend
# or
make release
```

The compiled assets are placed in `public/js/` with a cache-busted
`public/index.html`.
