.PHONY: all test test-server test-web build clean

all: build

test: test-server test-web

test-server:
	$(MAKE) -C server test

test-web:
	$(MAKE) -C web test

build:
	$(MAKE) -C server uber
	$(MAKE) -C web release

clean:
	$(MAKE) -C server clean
	$(MAKE) -C web clean
