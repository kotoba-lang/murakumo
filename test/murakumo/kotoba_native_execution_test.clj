;; A decision core, executed as a real native process.
;;
;; `kotoba_native_qualification_test` asks the backends to ACCEPT every core. It
;; does not ask whether one RUNS. Those are different questions, and only the
;; second one is what "native" is for -- a module can be admitted, emitted and
;; sealed and still compute the wrong number.
;;
;; ADR-260811's addendum records this being done by hand once, from a scratch
;; directory, and calls that a demonstration rather than a capability because
;; nothing re-ran it. This is the re-running.
;;
;; ── what this test does and does not prove ──
;;
;; It proves the emitted machine code computes what the cljc it was extracted
;; from computes. It does NOT prove the JDK-free property: this test is itself
;; running in a JVM, so it cannot demonstrate the absence of one. That belongs
;; to amu's `scripts/jdk-free-native-conformance.cljs`, which shadows
;; java/javac/clojure/clj and asserts none was called. Two separate claims, two
;; separate places, neither standing in for the other.
;;
;; ── why `cc` missing is a failure and not a skip ──
;;
;; A native execution test that does not execute has verified nothing. Skipping
;; quietly would leave a green suite asserting a property no one checked, which
;; is the exact shape of the hole this file exists to close. Every fleet node is
;; a Mac with the Xcode command line tools, so the compiler is present where
;; this runs.

(ns murakumo.kotoba-native-execution-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [murakumo.infer.prices :as prices]))

(def ^:private host-isa
  (let [arch (str/lower-case (System/getProperty "os.arch"))]
    (cond
      (contains? #{"aarch64" "arm64"} arch) "aarch64"
      (contains? #{"amd64" "x86_64"} arch) "x86_64"
      :else (throw (ex-info "unsupported host architecture for native execution"
                            {:os.arch arch})))))

(def ^:private target
  (keyword (str host-isa "-kotoba-v1")))

(defn- amu-checkout
  "amu's git checkout, from the pin this repository declares.

  Read from deps.edn rather than probed: the loader compiled here must belong to
  the compiler that emitted the code, and a directory found by searching the
  cache could be any other version sitting in it.

  Found by URL, not by coordinate name. The repository was renamed
  compiler -> amu and the coordinate did not move with it, so
  `io.github.kotoba-lang/compiler` still points at `amu.git`. tools.deps keys
  the gitlibs directory by COORDINATE, so both halves have to come from the same
  entry -- looking one up by a guessed name would find the pin and then the
  wrong directory, or neither."
  []
  (let [deps (get-in (edn/read-string (slurp "deps.edn"))
                     [:aliases :test :extra-deps])
        [coordinate {:keys [git/sha]}]
        (first (filter (fn [[_ {:keys [git/url]}]]
                         (and url (or (str/ends-with? url "/amu.git")
                                      (str/ends-with? url "/compiler.git"))))
                       deps))]
    (when-not sha
      (throw (ex-info "deps.edn's :test alias declares no amu/compiler git dep"
                      {:extra-deps (keys deps)})))
    (io/file (System/getProperty "user.home")
             ".gitlibs" "libs" (namespace coordinate) (name coordinate) sha)))

(defn- build-loader! [dir]
  (let [source (io/file (amu-checkout) "tools" "kexe_loader.c")
        out (io/file dir "kexe-loader")]
    (is (.exists source)
        (str "amu's loader source is missing at " source
             " — run `clojure -P -M:test` to fetch the pinned checkout"))
    (let [{:keys [exit err]} (shell/sh "cc" "-std=c11" "-O2" "-Wall" "-Wextra" "-Werror"
                                       (str source) "-o" (str out))]
      (is (zero? exit) (str "cc failed building the kexe loader:\n" err)))
    out))

(defn- emit-core!
  "Compile a core for the host ISA and write its raw code bytes, exactly as
  `kotoba -M extract-native` does: the artifact's `:code` is the image and its
  `:exports` carry each symbol's offset."
  [dir module]
  (let [artifact (:artifact (compiler/compile-source
                             (slurp (io/file "kotoba" module)) target {}))
        out (io/file dir (str module ".bin"))]
    (with-open [stream (io/output-stream out)]
      (.write stream (byte-array (map unchecked-byte (:code artifact)))))
    {:bin out :exports (:exports artifact)}))

(defn- call-native
  "Run one exported symbol in a fresh native process and return its result."
  [loader bin export args]
  (let [{:keys [exit out err]}
        (apply shell/sh (str loader) (str bin) (str (:offset export))
               (str (count args)) host-isa "-" (map str args))]
    (is (zero? exit) (str "kexe-loader exited " exit ":\n" err))
    ;; Guard the vacuous pass: empty stdout compared against an empty expected
    ;; value would agree. Assert there IS an answer before comparing it.
    (is (seq (str/trim out)) (str "kexe-loader produced no output; stderr:\n" err))
    (str/trim out)))

(def ^:private date-corpus
  ;; The same dates the cljc parity test uses, for the same reasons: the epoch,
  ;; both sides of the March boundary the algorithm shifts the year at,
  ;; 2000-02-29 against 1900-03-01 (the 400-year rule overriding the 100-year
  ;; one), and two pre-epoch dates so the negative branch is executed rather
  ;; than assumed.
  [[1970 1 1] [1970 3 1] [1970 2 28] [2000 2 29] [1900 3 1]
   [2024 2 29] [2026 8 11] [2099 12 31] [2100 3 1]
   [1969 12 31] [1600 1 1]])

(def ^:private cljc-days-between @#'prices/days-between)

(deftest a-decision-core-computes-the-same-answers-as-a-native-process
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "murakumo-native-exec"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (let [loader (build-loader! dir)
            {:keys [bin exports]} (emit-core! dir "prices_core.kotoba")
            export (get exports 'civil-days)]
        (is (some? export)
            (str "prices_core exports no civil-days; exported: " (keys exports)))
        (is (= 3 (:arity export)) "civil-days takes (y m d)")
        (doseq [[y m d :as date] date-corpus]
          (testing (format "%04d-%02d-%02d" y m d)
            (is (= (str (cljc-days-between "1970-01-01"
                                           (format "%04d-%02d-%02d" y m d)))
                   (call-native loader bin export [y m d]))))))
      (finally
        (doseq [f (reverse (file-seq dir))] (.delete ^java.io.File f))))))
