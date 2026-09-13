# structured-data-logger

A mobile-friendly application for collecting and analyzing structured,
tagged data as journal entries with persistent SCI code dashboards and
XTDB-backed server sync.

## Project Structure

- `web/` - ClojureScript Single Page Application (shadow-cljs, Helix, SCI,
  LocalStorage offline-first caching, cache-busting build hooks).
- `server/` - Clojure backend (deps.edn, HTTP-Kit, Reitit, Buddy Auth,
  XTDB v2 database, Quadlet container runner).

## Features

- **Structured Journal Entries**:
  - Always includes timestamp and description.
  - Arbitrary key:value pairs (numbers, free-form text, dropdown values).
  - Smart suggestions: chips for recently- and commonly-used keys and values.
  - Adding whole new keys on the fly.
- **SCI Analytics Dashboards**:
  - Persistent dashboards powered by the Small Clojure Interpreter (`sci`).
  - Starter scripts: counts by key, averages & stddev, intervals between
    entries, time window filters, and graphical bar charts.
- **Sync & Offline Support**:
  - Works entirely offline using browser LocalStorage.
  - Track changes and sync updates to the XTDB backend with Basic Auth.

## Running Tests

### All Tests

```bash
make test
```

### Server Tests

```bash
cd server
clj -T:build test
# or
make test
```

### Web Tests

```bash
cd web
npm install
npx shadow-cljs compile test && node out/node-tests.js
# or
make test
```

## Running the Application Locally

### 1. Server

```bash
cd server
clj -M:run-m 8000
```

To run with an external XTDB instance:
```bash
clj -M:run-m 8000 localhost
```

### 2. Web Client

```bash
cd web
npm install
npx shadow-cljs watch frontend
```

Open `http://localhost:3000` in your browser.

## Container & Quadlet Deployment

Build the server container image:
```bash
cd server
make container
```

Run via Podman Quadlet:
```bash
cd server
make run-server
```
