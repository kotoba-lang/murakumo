# Mac curl response-size diagnostic

On 2026-11-04, a short synthetic loopback HTTP server and `/usr/bin/curl`
were run on jacob (macOS build `25D771280a`) and on Tailscale hosts naphtali,
benjamin and judah (each arm64, build `25G332`). The three targets reported
curl 8.7.1 with SecureTransport, LibreSSL 3.3.6 and nghttp2 1.68.1; jacob
reported curl 8.7.1 and nghttp2 1.68.0. The exact diagnostic source was
`probe.py`, SHA-256
`e9d2e5dec28ae118b7f4226a8ad5aee0c81692e178d7420adfa062c4ee23fa4f`.

For each host, limits of 1024 and 262144 bytes were tested with Content-Length,
chunked encoding and a connection-close-delimited response. Exactly-at-limit
responses completed with exit 0 and the expected number of bytes. Responses
32768 bytes above the limit returned curl exit 63, with at most the specified
limit written. Known oversized Content-Length produced no output file bytes;
chunked and close-delimited cases left a partial file at the limit. Both cases
must be treated as failure, never as a completed inference response. Each
server bound a temporary loopback port; target staging and output were removed
and cleanup reported in-band because SSH exit status alone is not reliable on
these hosts.

This is a selected curl/version/HTTP framing diagnostic. It does not test
compressed bodies, redirects, network/authentication errors, disk-full,
concurrent invocations, a Unix socket, the model server, production process
supervision, crash recovery or a governor. It does not establish behavior of
the existing Linux `systemd-run` transport and has not been added to the
resident command. An actual Mac transport must bind the executable/version,
protect its output path, handle nonzero/unknown completion without accepting a
partial file, and qualify workload lifetime and aggregate disk usage.
