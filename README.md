# Structured Data Journal

A mobile-friendly application for collecting and reporting on structured,
tagged data as journal entries with persistent SCI code dashboards and
XTDB-backed server synchronization.

## Architecture Overview

The repository is structured into two main components:

- **[`web/`](web/README.md)**: Web frontend built with ClojureScript,
  Helix (React 18), shadow-cljs, and the Small Clojure Interpreter (SCI).
  Supports offline-first operation with LocalStorage and build-time
  cache busting.
- **[`server/`](server/README.md)**: Backend REST API server built with
  Clojure, HTTP-Kit, Reitit, Buddy Auth, and XTDB v2. Includes Podman
  Kubernetes Quadlet files for rootless container deployment.

## Core Capabilities

- **Timestamped Journal Entries**:
  - HTML5 `datetime-local` picker for precise timestamps.
  - Multiline `textarea` for notes and descriptions.
  - Arbitrary key:value attributes with native `<datalist>` autocomplete.
  - Blended suggestion chips for recently and frequently used keys and values.
  - Automatically captures pending input fields on save.
- **Persistent SCI Analytics Dashboards**:
  - In-browser evaluation of user Clojure code via SCI.
  - Built-in helper analytics: averages, standard deviations, intervals,
    frequencies, and date window filters.
  - Graphical bar chart visualizations rendered from user query results.
- **Bi-Directional Offline Sync**:
  - Fully functional offline via browser LocalStorage.
  - Tracks unsynced changes and synchronizes updates to the backend via
    HTTP Basic Auth.

## Quick Start

### Running All Tests

Run both server and web test suites from the repository root:

```bash
make test
```

### Running the Services Locally

1. **Start the Backend Server**:
   ```bash
   cd server
   clj -M:run-m 6000
   ```
   *(See [server/README.md](server/README.md) for full server details.)*

2. **Start the Web Frontend**:
   ```bash
   cd web
   npm install
   npx shadow-cljs watch frontend
   ```
   Open [http://localhost:3000](http://localhost:3000) in your browser.
   *(See [web/README.md](web/README.md) for full frontend details.)*

### Running the Server Container

Build and run the containerized backend:

```bash
# 1. Build the container image
cd server && make container

# 2. Run interactively in the foreground (press Ctrl+C to stop):
make run-container
# or:
#   podman run --rm -it -p 6000:6000 -e port=6000 \
#     localhost/structured-data-logger-server:latest

# Or run the combined server + XTDB pod:
make run-server
# (Stop with: make stop-server)
```

*(See [server/README.md](server/README.md#running-the-server-container)
for detached background and Quadlet instructions.)*

### Building Everything

Build the server uberjar and optimized web release bundle:

```bash
make build
```

## Detailed Documentation

- [Web Frontend Guide (web/README.md)](web/README.md)
- [Backend Server Guide (server/README.md)](server/README.md)
- [Specification (SPEC.md)](SPEC.md)
