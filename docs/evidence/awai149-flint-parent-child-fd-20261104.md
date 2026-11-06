# Flint parent/child FD diagnostic

On 2026-11-04, short-lived Python diagnostics ran sequentially on naphtali,
benjamin and judah. Each created its own mode-0700 temporary root and child,
opened the root directory and then the child by `dir_fd` with `O_DIRECTORY |
O_NOFOLLOW`, and compared the child's `fstat` device/inode with a no-follow
`stat` of the parent entry. All three matched, were on the same device and had
distinct inodes.

For a negative control the child entry was renamed, a new directory was put
at the same name, and the no-follow parent-entry identity no longer matched
the still-open original child FD on all three hosts. A separate symlink entry
was reported as a symlink by no-follow stat; opening it with `O_DIRECTORY |
O_NOFOLLOW` failed with errno 20 on all three. Only task-owned temporary
objects were changed; the temporary directories and descriptors were cleaned
up. No model, service, compiler build or deployment was run.

A separate read-only observation of naphtali's existing converted Flint
directory found that its `.kapitsa` parent entry matched its child FD before
and after inspection. Both observed directories were mode 0755, so this does
not meet the candidate packer's private-directory requirement.

These experiments suggest a host can collect the required parent-child
evidence, but do not establish the native Amu helper, bind directory snapshots
or subsequent file reads to the same FDs, exclude writable peers or hard-link
aliases, or make the identity comparison atomic with future use. The existing
ADI2 helper reports one open directory identity and listing; it does not
perform this parent-child check. See [AWAI-149](https://linear.app/kotobalabs/issue/AWAI-149).
