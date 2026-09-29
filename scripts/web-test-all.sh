#!/bin/sh
# Run every web-plane test namespace; non-zero if any fails.  (ADR-260929)
set -u
cd "$(dirname "$0")/.."
fail=0
for ns in web web-worker web-robots web-crawl web-search web-verify web-index web-host; do
  out=$(kbb -M:test -n "murakumo.$ns-test" 2>&1)
  line=$(printf '%s\n' "$out" | grep '^kbb test:' | tail -1)
  echo "$ns: ${line:-NO RESULT}"
  case "$line" in *" 0 fail, 0 error"*) ;; *) fail=1; printf '%s\n' "$out" | tail -25 ;; esac
done
exit $fail
