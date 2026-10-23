SRC_FILES := $(shell find src test deps.edn build.clj -type f)

all: container

.PHONY: clean run-server start-server stop-server \
	run-datahike start-datahike stop-datahike \
	run-xtdb start-xtdb stop-xtdb \
	migrate-to-datahike test uber container

test: $(SRC_FILES)
	clj -T:build test

uber: target/.uberjar-built
target/.uberjar-built: $(SRC_FILES)
	clj -T:build uber
	touch target/.uberjar-built

container: test target/.uberjar-built Containerfile
	podman build -t structured-data-logger-server:latest .

clean:
	clj -T:build clean
	rm -f target/.uberjar-built

run-container: container
	podman run --rm -it -p 6000:6000 -e port=6000 structured-data-logger-server:latest

run-datahike:
	podman kube play --wait structured-data-logger-datahike.yaml

start-datahike:
	podman kube play structured-data-logger-datahike.yaml

stop-datahike:
	podman kube down structured-data-logger-datahike.yaml

run-server:
	podman kube play --wait structured-data-logger-server.yaml

start-server:
	podman kube play structured-data-logger-server.yaml

stop-server:
	podman kube down structured-data-logger-server.yaml

run-xtdb:
	podman kube play --wait structured-data-logger-xtdb.yaml

start-xtdb:
	podman kube play structured-data-logger-xtdb.yaml

stop-xtdb:
	podman kube down structured-data-logger-xtdb.yaml

migrate-to-datahike:
	./bin/migrate-xtdb-to-datahike
