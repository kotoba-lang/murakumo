#!/bin/sh
# Run every web-plane test namespace; non-zero if any fails.  (ADR-260929)
set -u
cd "$(dirname "$0")/.."
fail=0
for ns in web web-worker web-robots web-crawl web-search web-backends web-verify web-index web-host web-provision web-origin; do
  out=$(kbb -M:test -n "murakumo.$ns-test" 2>&1)
  line=$(printf '%s\n' "$out" | grep '^kbb test:' | tail -1)
  echo "$ns: ${line:-NO RESULT}"
  case "$line" in *" 0 fail, 0 error"*) ;; *) fail=1; printf '%s\n' "$out" | tail -25 ;; esac
done
# The MCP server: handshake, tool list, and that credentials/bad arguments are refused
# (no network involved).
mcp=$(printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"t","version":"0"}}}' '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"web_fetch","arguments":{"url":"https://example.com/","sa_token":"kb_sa_x"}}}' '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"web_crawl","arguments":{"seeds":[]}}}' | timeout 30 node scripts/web_mcp.mjs 2>&1)
case "$mcp" in
  *'"web_provision"'*'credentials never travel through tool arguments'*'must be an array of short strings'*) echo "mcp server: ok" ;;
  *) echo "mcp server: FAILED: $mcp" | head -3; fail=1 ;;
esac
# An explicit unknown node name is refused, never mapped to another host (no network involved).
cli=$(MURAKUMO_REPO="$PWD" kbb --classpath "src:test" scripts/web-cli.cljk fetch '{:node "no-such-node" :job-id "t" :url "https://example.com/"}' 2>&1 | tail -1)
case "$cli" in *":cli/unknown-node"*) echo "web-cli unknown node: ok" ;; *) echo "web-cli unknown node: FAILED: $cli"; fail=1 ;; esac
# Python side of the Modal node must at least compile.
python3 -m py_compile deploy/modal/web_node.py scripts/modal_call.py 2>/dev/null \
  && echo "modal python: compiles" || { echo "modal python: COMPILE FAILED (or modal not installed for python3)"; }
# The nodes run STOCK nbb, which the kbb tests above do not exercise. Build the
# bundle and run the selftest op on it (no network needed).
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
scripts/web-bundle.sh "$tmp/bundle" >/dev/null
out=$(cd "$tmp/bundle" && MURAKUMO_WEB_HOME="$tmp/home" sh -c 'echo "{:op :selftest}" | nbb -cp . web_node.cljs' 2>&1 | tail -1)
case "$out" in
  *":resolve-localhost [\""*":resolve-dash []"*":sign-verify true"*":traversal-blocked true"*":ipv6-private true"*)
    echo "stock-nbb selftest: ok" ;;
  *) echo "stock-nbb selftest: FAILED: $out"; fail=1 ;;
esac
# The public origin (web_origin.cljs) under stock nbb: auth, request policy and the
# no-opted-in-node refusal, on a loopback port (no internet involved).
ohome="$tmp/origin"; mkdir -p "$ohome"; chmod 700 "$ohome"
otok=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
printf '%s' "$otok" > "$ohome/origin-token"; chmod 600 "$ohome/origin-token"
oport=$((20000 + $$ % 20000))
echo "{:port $oport :nodes [{:transport :local :web-home \"$tmp/home\" :bundle \"$tmp/bundle\"}]}" > "$ohome/origin.edn"
( cd "$tmp/bundle" && MURAKUMO_WEB_ORIGIN_HOME="$ohome" exec nbb -cp . web_origin.cljs ) > "$tmp/origin.log" 2>&1 &
opid=$!
i=0; until curl -s "http://127.0.0.1:$oport/health" >/dev/null 2>&1 || [ $i -ge 50 ]; do i=$((i+1)); sleep 0.2; done
oc() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
r1=$(oc "http://127.0.0.1:$oport/health")
r2=$(oc -H "Authorization: Bearer wrong-token-wrong-token-wrong-tok" "http://127.0.0.1:$oport/health")
r3=$(oc -H "Authorization: Bearer $otok" "http://127.0.0.1:$oport/health")
r4=$(oc -X POST -H "Authorization: Bearer $otok" -H "X-Murakumo-Sub: t" --data '{"kind":"scrape","url":"http://127.0.0.1/"}' "http://127.0.0.1:$oport/v1/web/jobs")
r5=$(oc -X POST -H "Authorization: Bearer $otok" -H "X-Murakumo-Sub: t" --data '{"kind":"scrape","url":"https://example.com/"}' "http://127.0.0.1:$oport/v1/web/jobs")
r6=$(oc -X POST -H "Authorization: Bearer $otok" --data '{"kind":"scrape","url":"https://example.com/"}' "http://127.0.0.1:$oport/v1/web/jobs")
kill $opid 2>/dev/null
case "$r1 $r2 $r3 $r4 $r5 $r6" in
  "401 401 200 400 503 400") echo "stock-nbb origin: ok" ;;
  *) echo "stock-nbb origin: FAILED (got $r1 $r2 $r3 $r4 $r5 $r6, want 401 401 200 400 503 400)"; tail -5 "$tmp/origin.log"; fail=1 ;;
esac
exit $fail
