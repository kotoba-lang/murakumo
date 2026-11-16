"""One-shot macOS per-process fatal-footprint setter, never a system setting.

Invoked by the target's own user through sudo -n. No service or global tuning.
XNU kern_memorystatus.h: SET=7, GET=8, FATAL=1, four 32-bit fields.
"""
(import argparse ctypes json os subprocess)

(defclass Limits [ctypes.Structure]
  (setv _fields_ [#("active" ctypes.c_int32) #("active_attr" ctypes.c_uint32)
                  #("inactive" ctypes.c_int32) #("inactive_attr" ctypes.c_uint32)]))

(defn identity [pid]
  (setv row (.strip (subprocess.check_output
    ["/bin/ps" "-p" (str pid) "-o" "uid=,ppid="] :text True)))
  (tuple (map int (.split row))))

(defn ensure [condition message]
  (when (not condition) (raise (RuntimeError message))))

(defn install [pid mib]
  (ensure (= (os.geteuid) 0) "requires one-shot privileged setter")
  (ensure (<= 64 mib 10240) "refuse invalid or over-10GiB limit")
  (setv owner (int (get os.environ "SUDO_UID"))
        [uid _] (identity pid)
        ancestor (os.getppid) found False)
  (ensure (and (> owner 0) (= uid owner)) "target must belong to invoking user")
  ;; sudo may have an intermediate monitor. Target must be a live ancestor,
  ;; not an arbitrary process selected by PID, and remains alive waiting on us.
  (for [_ (range 5)]
    (when (= ancestor pid) (setv found True) (break))
    (when (<= ancestor 1) (break))
    (setv [_ ancestor] (identity ancestor)))
  (ensure found "target must be this setter's invoking ancestor")
  (setv libc (ctypes.CDLL "/usr/lib/libSystem.B.dylib" :use_errno True)
        call libc.memorystatus_control
        call.argtypes [ctypes.c_uint32 ctypes.c_int32 ctypes.c_uint32
                       ctypes.c_void_p ctypes.c_size_t]
        call.restype ctypes.c_int)
  (defn invoke [command values]
    (when (!= (call command pid 0 (ctypes.byref values) (ctypes.sizeof values)) 0)
      (setv errno (ctypes.get_errno))
      (raise (OSError errno (os.strerror errno)))))
  (setv previous (Limits))
  (invoke 8 previous)
  (for [old [previous.active previous.inactive]]
    (when (and (> old 0) (< old mib))
      (raise (RuntimeError "refuse increasing an existing smaller process limit"))))
  (setv desired (Limits mib 1 mib 1))
  (invoke 7 desired)
  (setv actual (Limits))
  (invoke 8 actual)
  (ensure (= [actual.active actual.active_attr actual.inactive actual.inactive_attr]
             [mib 1 mib 1]) "kernel fatal limit readback mismatch")
  {"backend" "xnu-memorystatus-fatal-footprint" "pid" pid "uid" uid
   "active_limit_mib" actual.active "inactive_limit_mib" actual.inactive
   "active_fatal" True "inactive_fatal" True "readback_verified" True})

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "--pid" :type int :required True)
  (.add_argument parser "--mib" :type int :required True)
  (setv args (.parse_args parser))
  (print (json.dumps (install args.pid args.mib))))
