# Structured Data Journal - Backend Server

Backend synchronization and persistence server for the Structured Data
Journal application, built with Clojure, HTTP-Kit, Reitit, Buddy Auth,
and XTDB v2.

## Features

- **Sync & Storage API**: REST endpoints for bi-directional journal syncing,
  merging changes, and document storage.
- **Authentication**: HTTP Basic Auth with hashed user credentials.
- **Database**: In-memory storage with optional continuous sync to XTDB v2.
- **Containerization**: Containerfile and Podman Kubernetes Quadlet
  definitions for container deployments.

## Getting Started

### Run Server in Development

Run on port 8000 with in-memory storage (press `Ctrl+C` to stop):

```bash
clj -M:run-m 8000
```

Run connected to an XTDB instance (press `Ctrl+C` to stop):

```bash
clj -M:run-m 8000 localhost
```

### Running Tests

Run the test suite using Cognitect test-runner:

```bash
clj -T:build test
# or
make test
```

### Building the Standalone Uberjar

```bash
clj -T:build uber
# or
make uber
```

The resulting JAR is written to:
`target/structured-data-logger-server-standalone.jar`

Run the compiled JAR:

```bash
java -jar target/structured-data-logger-server-standalone.jar 8000
```

## API Endpoints & Examples

### 1. Health Check (Ping)

```bash
curl -s http://localhost:8000/journal/api/ping
# Response: pong
```

### 2. User Registration

```bash
curl -s -X POST \
  -H 'Content-Type: application/json' \
  -d '{"id":"alice","login":"alice","password":"secret123"}' \
  http://localhost:8000/journal/api/register
```

### 3. Sync Journal Entries

Send a batch of journal entries or deletions:

```bash
curl -s -u alice:secret123 -X POST \
  -H 'Content-Type: application/json' \
  -d '{"changes":[{"id":"entry-1",
                   "timestamp":"2026-09-14T20:00:00Z",
                   "description":"Jogged 3 miles",
                   "data":{"mileage":3.0,"shoes":"running"}}]}' \
  http://localhost:8000/journal/api/sync/alice
```

### 4. Fetch Synced Entries

```bash
curl -s -u alice:secret123 \
  http://localhost:8000/journal/api/document/alice
```

## Running the Server Container

### 1. Build the Container Image

Build the server uberjar and container image:

```bash
make container
```

This tags the image as `localhost/structured-data-logger-server:latest`.

### 2. Run Interactively in Foreground (Press Ctrl+C to Stop)

To run the container interactively in the foreground attached to your
terminal with live log output:

```bash
make run-container
```

Or run directly with `podman`:

```bash
podman run --rm -it -p 8000:8000 -e port=8000 \
  localhost/structured-data-logger-server:latest
```

Press `Ctrl+C` at any time to cleanly stop and remove the container.

To run interactively connected to an external XTDB host:

```bash
podman run --rm -it -p 8000:8000 -e port=8000 \
  -e dbhost=host.containers.internal \
  localhost/structured-data-logger-server:latest
```

### 3. Run in the Background (Detached)

Alternatively, to run detached in the background:

```bash
podman run -d --name structured-data-logger-server \
  -p 8000:8000 \
  -e port=8000 \
  localhost/structured-data-logger-server:latest
```

View logs:

```bash
podman logs -f structured-data-logger-server
```

Stop and remove:

```bash
podman stop structured-data-logger-server
podman rm structured-data-logger-server
```

### 3. Run Server + XTDB Pod via Kubernetes YAML

Run both the server and XTDB 2.1 together in a pod:

```bash
make run-server
# or
podman kube play --wait structured-data-logger-server.yaml
```

Stop the server pod:

```bash
make stop-server
# or
podman kube down structured-data-logger-server.yaml
```

### 4. Run Standalone XTDB Pod

```bash
make run-xtdb
# or
podman kube play --wait structured-data-logger-xtdb.yaml

# Stop:
make stop-xtdb
# or
podman kube down structured-data-logger-xtdb.yaml
```

### 5. Install as a Systemd User Quadlet

For automated background startup on boot:

```bash
loginctl enable-linger
mkdir -p $HOME/.config/containers/systemd
cp structured-data-logger-server.kube \
   structured-data-logger-server.yaml \
   $HOME/.config/containers/systemd/
systemctl --user daemon-reload
systemctl --user start structured-data-logger-server
systemctl --user status structured-data-logger-server
```
