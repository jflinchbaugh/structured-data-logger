# Structured Data Journal - Backend Server

Backend synchronization and persistence server for the Structured Data
Journal application, built with Clojure, HTTP-Kit, Reitit, Buddy Auth,
Datahike, and XTDB v2.

## Features

- **Sync & Storage API**: REST endpoints for bi-directional journal syncing,
  merging changes, and document storage.
- **Authentication**: HTTP Basic Auth with hashed user credentials.
- **Database**: In-memory storage with optional continuous sync to Datahike
  (durable Datalog file storage) or XTDB v2.
- **Containerization**: Containerfile and Podman Kubernetes Quadlet
  definitions for container deployments.

## Getting Started

### Run Server in Development

Run on port 6000 with in-memory storage (press `Ctrl+C` to stop):

```bash
clj -M:run-m 6000
```

Run connected to Datahike (file-based persistence):

```bash
clj -M:run-m 6000 datahike /path/to/datahike-storage
```

Run connected to an XTDB instance:

```bash
clj -M:run-m 6000 xtdb localhost
# or simply
clj -M:run-m 6000 localhost
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
java -jar target/structured-data-logger-server-standalone.jar 6000
```

## API Endpoints & Examples

### 1. Health Check (Ping)

```bash
curl -s http://localhost:6000/journal/api/ping
# Response: pong
```

### 2. User Registration

```bash
curl -s -X POST \
  -H 'Content-Type: application/json' \
  -d '{"id":"alice","login":"alice","password":"secret123"}' \
  http://localhost:6000/journal/api/register
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
  http://localhost:6000/journal/api/sync/alice
```

### 4. Fetch Synced Entries

```bash
curl -s -u alice:secret123 \
  http://localhost:6000/journal/api/document/alice
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
podman run --rm -it -p 6000:6000 -e port=6000 \
  localhost/structured-data-logger-server:latest
```

Press `Ctrl+C` at any time to cleanly stop and remove the container.

To run interactively connected to an external XTDB host:

```bash
podman run --rm -it -p 6000:6000 -e port=6000 \
  -e dbhost=host.containers.internal \
  localhost/structured-data-logger-server:latest
```

To run interactively with Datahike mounted storage:

```bash
podman run --rm -it -p 6000:6000 -e port=6000 \
  -e backend=datahike -e target=/var/lib/datahike \
  -v structured-data-logger-datahike-data:/var/lib/datahike \
  localhost/structured-data-logger-server:latest
```

### 3. Run in the Background (Detached)

Alternatively, to run detached in the background:

```bash
podman run -d --name structured-data-logger-server \
  -p 6000:6000 \
  -e port=6000 \
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

### 4. Run Server with Datahike Pod via Kubernetes YAML

Run the server with Datahike and an ephemeral volume in a foreground pod:

```bash
make run-datahike
# or
podman kube play --wait structured-data-logger-datahike.yaml
```

Run the server with Datahike and a durable volume in a background pod:

```bash
make start-datahike
# or
podman kube play structured-data-logger-datahike.yaml
```

Stop the Datahike server pod:

```bash
make stop-datahike
# or
podman kube down structured-data-logger-datahike.yaml
```

### 5. Run Server + XTDB Pod via Kubernetes YAML

Run both the server and XTDB 2.2 together in a foreground pod
with ephemeral storage:

```bash
make run-server
# or
podman kube play --wait structured-data-logger-server.yaml
```

Run both the server and XTDB 2.2 together in a background pod
with durable storage:

```bash
make start-server
# or
podman kube play structured-data-logger-server.yaml
```

Stop the server pod:

```bash
make stop-server
# or
podman kube down structured-data-logger-server.yaml
```

### 6. Run Standalone XTDB Pod

In a foreground pod with ephemeral storage:

```bash
make run-xtdb
# or
podman kube play --wait structured-data-logger-xtdb.yaml
```

In a background pod with durable storage:

```bash
make start-xtdb
# or
podman kube play structured-data-logger-xtdb.yaml
```

Stop the XTDB pod:

```bash
make stop-xtdb
# or
podman kube down structured-data-logger-xtdb.yaml
```

### 7. Backup, Restore, and Database Migration (Dump & Load)

The server jar includes built-in `dump` and `load` commands, and the container image provides `/usr/local/bin/dump-db` and `/usr/local/bin/load-db` for streaming backup/restore across any supported database backend.

#### Dump Database to EDN

Dump from Datahike:

```bash
# Locally:
java -jar target/structured-data-logger-server-standalone.jar dump datahike /var/lib/datahike > backup.edn

# Via Podman container:
podman run --rm -v structured-data-logger-datahike-data:/var/lib/datahike \
  localhost/structured-data-logger-server:latest dump-db datahike /var/lib/datahike > backup.edn
```

Dump from XTDB:

```bash
# Locally (when XTDB is running on localhost):
java -jar target/structured-data-logger-server-standalone.jar dump xtdb localhost > backup.edn

# Via Podman container (networked to XTDB):
podman run --rm --network host \
  localhost/structured-data-logger-server:latest dump-db xtdb localhost > backup.edn
```

#### Load / Restore Database from EDN

Load into Datahike:

```bash
# Locally:
java -jar target/structured-data-logger-server-standalone.jar load datahike /var/lib/datahike < backup.edn

# Via Podman container:
podman run --rm -i -v structured-data-logger-datahike-data:/var/lib/datahike \
  localhost/structured-data-logger-server:latest load-db datahike /var/lib/datahike < backup.edn
```

Load into XTDB:

```bash
# Locally:
java -jar target/structured-data-logger-server-standalone.jar load xtdb localhost < backup.edn

# Via Podman container:
podman run --rm -i --network host \
  localhost/structured-data-logger-server:latest load-db xtdb localhost < backup.edn
```

#### Automated Migration Using Kube Play Pod (`make migrate-to-datahike`)

If you want to start the XTDB server pod automatically via `podman kube play`, dump all journals, load them into the Datahike volume, and shut down XTDB cleanly:

```bash
make migrate-to-datahike
# or
./bin/migrate-xtdb-to-datahike
```

This script:
1. Starts `structured-data-logger-server.yaml` via `podman kube play`.
2. Waits until XTDB is responding.
3. Dumps journals to `backup-migration.edn`.
4. Loads them into the `structured-data-logger-datahike-data` Podman volume.
5. Shuts down the XTDB pod via `podman kube down`.

#### Direct Migration from XTDB to Datahike (Pipe)

You can migrate data directly between running backends by piping `dump-db` into `load-db`:

```bash
# Dump from XTDB and load straight into Datahike:
podman run --rm --network host \
  localhost/structured-data-logger-server:latest dump-db xtdb localhost | \
podman run --rm -i -v structured-data-logger-datahike-data:/var/lib/datahike \
  localhost/structured-data-logger-server:latest load-db datahike /var/lib/datahike
```

### 8. Install as a Systemd User Quadlet

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
