"""The replica's poll on the clock (docs/topology-replica.md, Poll on the clock).

For each image named on the command line, start one replica that polls once,
3000 ms after it starts serving, with no peer (fanout 0), and ask it for
/topology every 50 ms until it exits. Print when it polled, counted from its
first answer: on time from 2.5 s on, early before. A test client only: it is
not part of any node path.
"""
import shutil, socket, subprocess, sys, time

g = sys.argv[1]
TRUST = "op1=did:key:z6MktwupdmLXVVqTzCw4i46r4uGyosGXRnR3XjN4Zq7oMMsw"
token = open(f"{g}/fleet.token").read().strip()
for i, name in enumerate(sys.argv[2:]):
    port = 7851 + i
    shutil.copy(f"{g}/topology.json", f"{g}/clock-{name}.json")
    env = {"PATH": "/usr/bin:/bin", "KEXE_ALLOW": "7,35,37,38,43,44,46,47",
           "KEXE_CAP_RESOURCES_35": g, "KEXE_CAP_RESOURCES_43": f"127.0.0.1:{port}"}
    log = open(f"{g}/clock-{name}.log", "wb")
    p = subprocess.Popen([f"{g}/{name}.elf", "replica", f"{g}/clock-{name}.json", TRUST, "1", f"{g}/fleet.token",
                          f"127.0.0.1:{port}", "3000", "1", "0"], env=env, stdout=log, stderr=log)
    first, served, deadline = None, 0, time.monotonic() + 60
    while p.poll() is None and time.monotonic() < deadline:
        try:
            c = socket.create_connection(("127.0.0.1", port), 0.5)
            c.settimeout(2)
            c.sendall(f"GET /topology HTTP/1.0\r\nauthorization: Bearer {token}\r\n\r\n".encode())
            data = b""
            while True:
                chunk = c.recv(65536)
                if not chunk:
                    break
                data += chunk
            c.close()
            if data.startswith(b"HTTP/1.0 200"):
                served += 1
                if first is None:
                    first = time.monotonic()
        except OSError:
            pass
        time.sleep(0.05)
    if p.poll() is None:
        p.kill()
        print(f"{name}: no poll within 60 s")
        continue
    if first is None:
        print(f"{name}: no answer, exit {p.returncode}")
        continue
    took = time.monotonic() - first
    when = "on time" if took >= 2.5 else "early"
    many = "more than 20" if served > 20 else "20 or fewer"
    print(f"{name}: polled {when}, {many} requests served, exit {p.returncode}")
