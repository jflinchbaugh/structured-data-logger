.PHONY: all install dev dev-test test release clean

all: release

install:
	npm install

dev: install
	npx shadow-cljs watch frontend

dev-test: install
	npx shadow-cljs watch frontend test

test: install
	npx shadow-cljs compile test && node out/node-tests.js

release: install
	npx shadow-cljs release frontend

clean:
	rm -rf out .shadow-cljs public/js public/index.html src/main/structured_data_logger/version.cljs
