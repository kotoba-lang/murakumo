(ns murakumo.infer-edge-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.infer.edge :as edge]))

(deftest renders-admitted-resident-plists
  (let [server (edge/server-plan
                {:home "/Users/asher" :llama-server "/opt/llama-server"
                 :memory-bytes (* 16 1073741824)})
        join (edge/join-plan
              {:home "/Users/asher" :nbb "/opt/homebrew/bin/nbb"
               :node-name "asher"})]
    (is (:admitted? server))
    (is (re-find #"<key>UserName</key><string>asher</string>" (:plist server)))
    (is (re-find #"<key>UserName</key><string>asher</string>" (:plist join)))
    (is (re-find #"--ctx-size</string><string>65536" (:plist server)))
    (is (not (re-find #"--spec-type" (:plist server))))
    (is (re-find #"murakumo-edge" (:plist server)))
    (is (re-find #"source /Users/asher/.murakumo/edge/join.env" (:plist join)))
    (is (not (re-find #"MURAKUMO_SERVICE_TOKEN=" (:plist join))))))

(deftest resident-fleet-joins-the-secure-pool
  ;; The ten fleet.edn minis are AWAI-operated hardware, which
  ;; docs/adr-secure-community-cloud.md defines as :awai-secure. The join
  ;; worker defaults to community on purpose, so the tier has to be spelled
  ;; here by whoever operates the machines.
  ;;
  ;; Measured 2026-08-29 with this line absent: /infer/nodes showed all ten
  ;; minis model=murakumo-edge, live? true, ready? true, slots-free 1, and
  ;; admission "pending" -- placement needs an exact tier match and there is no
  ;; Secure-to-Community fallback, so murakumo-edge had zero admitted backends
  ;; and every Bot turn failed provider/http-error 503 against a healthy fleet.
  (let [join (edge/join-plan
              {:home "/Users/asher" :nbb "/opt/homebrew/bin/nbb"
               :node-name "asher"})]
    (is (re-find #"--trust-tier awai-secure" (:plist join))
        "the resident daemon must declare the tier; omission enrolls Community")
    ;; And it must not be smuggled in as a bare word that happens to appear:
    ;; the flag has to precede the value the join worker validates.
    (is (re-find #"--trust-tier awai-secure --local-url" (:plist join)))))
