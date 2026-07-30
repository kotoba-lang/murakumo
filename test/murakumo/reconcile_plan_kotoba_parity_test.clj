;; W6 pure-planner oracle: murakumo.reconcile.plan scalar decisions
;; vs kotoba/reconcile_plan_core.kotoba.

(ns murakumo.reconcile-plan-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.reconcile.plan :as r]))

(def port-source (slurp "kotoba/reconcile_plan_core.kotoba"))

(def export-prefix
  (str "default-replicas desired deficit watch-sleep-ms action-name "
       "better-target? first-of-2 first-of-3 name-order-record "
       "pick-targets-2-record pick-targets-3-first "
       "targets-first targets-second targets-count "
       "blank? missing-manifest? action-is-satisfied? action-is-place? "
       "default-watch-seconds starts-with? "
       "flag-is-dry-run? flag-is-apply? flag-is-watch? flag-is-snapshot? "
       "flag-is-dash? watch-seconds snapshot-value "
       "digit-val? digit-of-go digit-of parse-digits-go parse-digits trim-ws "
       "flag-dry-run flag-apply flag-watch flag-watch-eq-prefix "
       "flag-snapshot-prefix flag-dash-prefix "
       "action-satisfied action-place action-over action-blocked "
       "action-needs-build reconcile-record-type"))
(def fleet
  {:fleet/name "test-mesh"
   :nodes [{:name "a" :roles ["pin" "compute"] :labels {:zone "jp" :tier "edge"}}
           {:name "b" :roles ["pin" "compute"] :labels {:zone "jp" :tier "edge"}}
           {:name "c" :roles ["pin" "compute"] :labels {:zone "jp" :tier "edge"}}
           {:name "d" :roles ["pin" "compute" "relay"] :labels {:zone "jp" :role "canary"}}
           {:name "e" :roles ["pin"] :labels {:zone "us"}}]})

(def snap
  {:nodes [{:name "a" :hosted ["bafyHEART"]}
           {:name "b" :hosted []}
           {:name "c" :hosted []}
           {:name "d" :hosted []}
           {:name "e" :hosted ["bafyHEART"]}]})

(defn- kotoba-literal [s]
  (str \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- compile-i64-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :i64 " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- compile-string-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first
                 #"\(:export \[[^\]]+\]\)"
                 (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- opt-i64-form [n]
  (if (nil? n)
    "(option-none-of [:option :i64])"
    (str "(option-some-of [:option :i64] " (long n) ")")))

(defn- opt-str-form [s]
  (if (nil? s)
    "(option-none-of [:option :string])"
    (let [lit (str \" (-> (str s)
                          (str/replace "\\" "\\\\")
                          (str/replace "\"" "\\\"")) \")]
      (str "(option-some-of [:option :string] " lit ")"))))

(defn- project-action-args
  "Host-project reconcile-app inputs into guest Product Value ABI options."
  [app snap]
  (let [decision (r/reconcile-app fleet snap nil app)
        running (count (:running decision))
        desired (or (:replicas app) 1)
        free (count (:targets decision))
        ;; when blocked, targets empty but candidates may be empty too
        free-candidates (if (= :blocked (:action decision))
                          0
                          (if (= :place (:action decision))
                            (max free 1)
                            free))]
    {:decision decision
     :cid (:cid app)
     :running running
     :desired desired
     :free-candidates free-candidates}))

(deftest defaults-and-deficit-and-sleep
  (let [actual (compile-i64-cases
                {"dr" "(default-replicas)"
                 "d0" (str "(desired " (opt-i64-form nil) ")")
                 "d1" (str "(desired " (opt-i64-form 3) ")")
                 "df" "(deficit 3 1)"
                 "df0" "(deficit 1 3)"
                 "sl" "(watch-sleep-ms 15)"})]
    (is (= 1 (get actual "dr")))
    (is (= 1 (get actual "d0")))
    (is (= 3 (get actual "d1")))
    (is (= 2 (get actual "df")))
    (is (= 0 (get actual "df0")))
    (is (= (r/watch-sleep-ms 15) (get actual "sl")))))

(deftest action-name-matches-reconcile-app
  (let [apps [{:name "heartbeat" :cid "bafyHEART" :replicas 3
               :placement {:labels {:zone "jp"} :roles ["compute"]}}
              {:name "ok" :cid "bafyHEART" :replicas 1
               :placement {:labels {:zone "jp"} :roles ["compute"]}}
              {:name "ghost" :cid "bafyGHOST" :replicas 1
               :placement {:labels {:zone "antarctica"}}}
              {:name "uncompiled" :replicas 1 :placement {}}]
        snaps [snap
               {:nodes [{:name "a" :hosted ["bafyHEART"]}
                        {:name "b" :hosted []} {:name "c" :hosted []}
                        {:name "d" :hosted []} {:name "e" :hosted []}]}
               snap
               snap]
        ;; over: two eligible running, desired 1
        over-app {:name "heartbeat" :cid "bafyHEART" :replicas 1
                  :placement {:labels {:zone "jp"} :roles ["compute"]}}
        over-snap {:nodes [{:name "a" :hosted ["bafyHEART"]}
                           {:name "b" :hosted ["bafyHEART"]}
                           {:name "c" :hosted []} {:name "d" :hosted []}
                           {:name "e" :hosted []}]}
        corpus (conj (mapv vector apps snaps) [over-app over-snap])
        cases (into {}
                    (map-indexed
                     (fn [i [app sn]]
                       (let [p (project-action-args app sn)]
                         [(str "a_" i)
                          (str "(action-name " (opt-str-form (:cid p)) " "
                               (:running p) " " (:desired p) " "
                               (:free-candidates p) ")")]))
                     corpus))
        actual (compile-string-cases cases)]
    (doseq [[i [app sn]] (map-indexed vector corpus)]
      (let [p (project-action-args app sn)
            expected (name (:action (:decision p)))]
        (testing (str (:name app) " → " expected)
          (is (= expected (get actual (str "a_" i)))))))))

(deftest pick-targets-pure-matches-cljc
  (let [pick @#'r/pick-targets
        ;; name order for ["c" "a" "b"]: indices 0=c,1=a,2=b; strings a < b < c
        ;; n01: c<=a? 0; n02: c<=b? 0; n12: a<=b? 1
        names-cab "(name-order-record false false true)"
        actual (compile-i64-cases
                {"f2" "(first-of-2 1 3 true)"  ;; load0=1 load1=3 name0 before → 0
                 "f2b" "(first-of-2 1 1 false)" ;; equal load, name0 after → 1
                 "f3" (str "(first-of-3 1 3 1 " names-cab ")")
                 "t3" (str "(pick-targets-3-first 1 3 1 " names-cab ")")
                 ;; T5.3: project record fields inside the guest
                 "pf" "(targets-first (pick-targets-2-record 1 3 true 2))"
                 "ps" "(targets-second (pick-targets-2-record 1 3 true 2))"
                 "pc" "(targets-count (pick-targets-2-record 1 3 true 2))"
                 "p2n1c" "(targets-count (pick-targets-2-record 1 3 true 1))"
                 "p2n0c" "(targets-count (pick-targets-2-record 1 3 true 0))"})
        ;; cljc: candidates c,a,b loads c=1,a=3,b=1 → sorted b,c,a
        cljc (pick ["c" "a" "b"] 2 {"c" 1 "a" 3 "b" 1})]
    (is (= 0 (get actual "f2")))
    (is (= 1 (get actual "f2b")))
    ;; first-of-3 with loads 1,3,1: compare c vs a: load c better → w01=0
    ;; compare c vs b: loads equal 1,1, name c<=b? false → n02=0 → b wins → first=2 (b)
    (is (= 2 (get actual "f3")))
    (is (= 2 (get actual "t3")))
    (is (= 0 (get actual "pf")))  ;; first of two with load1=1,3 name0 before → 0
    (is (= 1 (get actual "ps")))
    (is (= 2 (get actual "pc")))
    (is (= 1 (get actual "p2n1c")))
    (is (= 0 (get actual "p2n0c")))
    (is (= ["b" "c"] cljc))
    ;; two-record: candidates order [b c] loads 1,1 names b before c
    (let [p2 (compile-i64-cases
              {"ord0" "(targets-first (pick-targets-2-record 1 1 true 2))"
               "ord1" "(targets-second (pick-targets-2-record 1 1 true 2))"
               "ordc" "(targets-count (pick-targets-2-record 1 1 true 2))"})]
      (is (= 0 (get p2 "ord0")))
      (is (= 1 (get p2 "ord1")))
      (is (= 2 (get p2 "ordc"))))))

(deftest flag-and-action-pure-match
  (let [i (compile-i64-cases
           {"mm0" (str "(if (missing-manifest? " (kotoba-literal "") ") 1 0)")
            "mm1" (str "(if (missing-manifest? " (kotoba-literal "app.edn") ") 1 0)")
            "as" (str "(if (action-is-satisfied? " (kotoba-literal "satisfied") ") 1 0)")
            "ap" (str "(if (action-is-place? " (kotoba-literal "place") ") 1 0)")
            "ao" (str "(if (action-is-place? " (kotoba-literal "satisfied") ") 1 0)")
            "dw" "(default-watch-seconds)"
            ;; Profile 5: flag-is-* are :bool
            "fd" (str "(if (flag-is-dry-run? " (kotoba-literal "--dry-run") ") 1 0)")
            "fa" (str "(if (flag-is-apply? " (kotoba-literal "--apply") ") 1 0)")
            "fw" (str "(if (flag-is-watch? " (kotoba-literal "--watch=5") ") 1 0)")
            "fs" (str "(if (flag-is-snapshot? " (kotoba-literal "--snapshot=x") ") 1 0)")
            "ws" (str "(watch-seconds " (kotoba-literal "--watch") ")")
            "w5" (str "(watch-seconds " (kotoba-literal "--watch=5") ")")})
        s (compile-string-cases
           {"sv" (str "(snapshot-value " (kotoba-literal "--snapshot=snap.edn") ")")
            "fdry" "(flag-dry-run)"
            "fapp" "(flag-apply)"
            "fwch" "(flag-watch)"
            "fweq" "(flag-watch-eq-prefix)"
            "fsp" "(flag-snapshot-prefix)"
            "fdp" "(flag-dash-prefix)"
            "asat" "(action-satisfied)"
            "aplc" "(action-place)"
            "aov" "(action-over)"
            "abl" "(action-blocked)"
            "anb" "(action-needs-build)"})]
    (is (= 1 (get i "mm0")))
    (is (= 0 (get i "mm1")))
    (is (= 1 (get i "as")))
    (is (= 1 (get i "ap")))
    (is (= 0 (get i "ao")))
    (is (= 30 (get i "dw")))
    (is (= 1 (get i "fd")))
    (is (= 1 (get i "fa")))
    (is (= 1 (get i "fw")))
    (is (= 1 (get i "fs")))
    (is (= 30 (get i "ws")))
    (is (= 5 (get i "w5")))
    (is (= "snap.edn" (get s "sv")))
    (is (= r/flag-dry-run (get s "fdry")))
    (is (= "--dry-run" (get s "fdry")))
    (is (= r/flag-apply (get s "fapp")))
    (is (= r/flag-watch (get s "fwch")))
    (is (= r/flag-watch-eq-prefix (get s "fweq")))
    (is (= "--watch=" (get s "fweq")))
    (is (= r/flag-snapshot-prefix (get s "fsp")))
    (is (= "--snapshot=" (get s "fsp")))
    (is (= r/flag-dash-prefix (get s "fdp")))
    (is (= r/action-satisfied (get s "asat")))
    (is (= "satisfied" (get s "asat")))
    (is (= r/action-place (get s "aplc")))
    (is (= r/action-over (get s "aov")))
    (is (= r/action-blocked (get s "abl")))
    (is (= r/action-needs-build (get s "anb")))
    (is (= :missing-manifest (r/reconcile-command-error {})))
    (is (nil? (r/reconcile-command-error {:manifest "murakumo.app.edn"})))
    (is (= {:manifest "murakumo.app.edn" :dry-run true :snapshot "snap.edn"}
           (r/parse-flags ["murakumo.app.edn" "--dry-run" "--snapshot=snap.edn"])))
    (is (= {:manifest "murakumo.app.edn" :apply true :watch 30}
           (r/parse-flags ["--apply" "--watch" "murakumo.app.edn"])))
    (is (= {:manifest "murakumo.app.edn" :watch 5}
           (r/parse-flags ["--ignored" "--watch=5" "murakumo.app.edn"])))))

(deftest reconcile-record-type-matches
  (let [s (compile-string-cases {"rrt" "(reconcile-record-type)"})]
    (is (= r/reconcile-record-type (get s "rrt")))
    (is (= "com.murakumo.fleet.reconcile" (get s "rrt")))
    (is (= "com.murakumo.fleet.reconcile"
           (:$type (r/reconcile-record
                    {:ts 1 :fleet "f" :apps []} "{}"))))))
