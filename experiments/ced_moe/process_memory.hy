"""Kernel process counters, separate from MLX's allocator statistics."""
(import ctypes os)

(defclass Usage [ctypes.Structure]
  (setv _fields_ (+ [#("uuid" (* ctypes.c_uint8 16))]
    (lfor name ["user_time" "system_time" "pkg_idle_wkups" "interrupt_wkups"
      "pageins" "wired_size" "resident_size" "phys_footprint" "proc_start_abstime"
      "proc_exit_abstime" "child_user_time" "child_system_time" "child_pkg_idle_wkups"
      "child_interrupt_wkups" "child_pageins" "child_elapsed_abstime" "diskio_bytesread"
      "diskio_byteswritten" "cpu_time_qos_default" "cpu_time_qos_maintenance"
      "cpu_time_qos_background" "cpu_time_qos_utility" "cpu_time_qos_legacy"
      "cpu_time_qos_user_initiated" "cpu_time_qos_user_interactive" "billed_system_time"
      "serviced_system_time" "logical_writes" "lifetime_max_phys_footprint"
      "instructions" "cycles" "billed_energy" "serviced_energy"
      "interval_max_phys_footprint" "runnable_time"] #(name ctypes.c_uint64)))))

(defn counters [[pid None]]
  (setv libc (ctypes.CDLL "/usr/lib/libproc.dylib" :use_errno True)
        call libc.proc_pid_rusage
        call.argtypes [ctypes.c_int ctypes.c_int ctypes.c_void_p]
        call.restype ctypes.c_int value (Usage))
  (when (!= (call (or pid (os.getpid)) 4 (ctypes.byref value)) 0)
    (raise (OSError (ctypes.get_errno) "proc_pid_rusage failed")))
  {"resident_bytes" value.resident_size "phys_footprint_bytes" value.phys_footprint
   "lifetime_peak_phys_footprint_bytes" value.lifetime_max_phys_footprint
   "disk_read_bytes" value.diskio_bytesread "disk_write_bytes" value.diskio_byteswritten})
