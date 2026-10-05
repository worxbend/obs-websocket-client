#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./mill --no-server coverage.reset
python3 tools/check_coverage.py --start
./mill --no-server '{codegen,protocol,core,sttp,okhttp,zio,fs2,pekko,examples,server}.test'
./mill --no-server '{codegen,protocol,core,sttp,okhttp,zio,fs2,pekko,examples,server}.scoverage.xmlReport'
./mill --no-server '{codegen,protocol,core,sttp,okhttp,zio,fs2,pekko,examples,server}.scoverage.htmlReport'
python3 tools/check_coverage.py
