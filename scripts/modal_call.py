#!/usr/bin/env python3
"""Invoke a murakumo web node hosted on Modal. stdin: one EDN request; stdout: one EDN line.

    modal_call.py <app-name> <function-name>

Uses the operator's own Modal credentials (the same ones `modal` uses); the
function has no public URL. Run with the Python that has `modal` installed.
"""
import sys

import modal


def main():
    app_name, fn_name = sys.argv[1], sys.argv[2]
    request = sys.stdin.read()
    fn = modal.Function.from_name(app_name, fn_name)
    sys.stdout.write(fn.remote(request).strip().splitlines()[-1] + "\n")


if __name__ == "__main__":
    main()
