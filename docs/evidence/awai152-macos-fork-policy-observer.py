# One-off observation only: this is not an operational governor.
import json, os
results = {}
try:
    pid = os.fork()
    if pid == 0:
        os._exit(0)
    _, status = os.waitpid(pid, 0)
    results['fork'] = 'allowed' if os.waitstatus_to_exitcode(status) == 0 else 'child-failed'
except OSError as error:
    results['fork'] = 'denied:' + str(error.errno)
try:
    pid = os.posix_spawn('/usr/bin/true', ['true'], os.environ)
    _, status = os.waitpid(pid, 0)
    results['posix_spawn'] = 'allowed' if os.waitstatus_to_exitcode(status) == 0 else 'child-failed'
except OSError as error:
    results['posix_spawn'] = 'denied:' + str(error.errno)
print(json.dumps(results, sort_keys=True))
