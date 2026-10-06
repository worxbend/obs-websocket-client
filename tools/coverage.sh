#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./mill --no-server -j 1 coverage.reset
python3 tools/check_coverage.py --start
./mill --no-server -j 1 codegen.test.coverage
./mill --no-server -j 1 '{protocol,core,sttp,okhttp,zio,fs2,pekko,examples,server}.test'
./mill --no-server -j 1 '{protocol,core,sttp,okhttp,zio,fs2,pekko,examples,server}.scoverage.xmlReport'
./mill --no-server -j 1 '{protocol,core,sttp,okhttp,zio,fs2,pekko,examples,server}.scoverage.htmlReport'
python3 tools/check_coverage.py
