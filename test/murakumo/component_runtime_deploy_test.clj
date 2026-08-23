(ns murakumo.component-runtime-deploy-test
  (:require [clojure.test :refer [deftest is testing]]
            [murakumo.component-runtime-deploy :as deploy]))

(def template
  "<plist>{{USER}} {{BIN}} {{COMPONENT}} {{COMPONENT_CID}} {{COMPONENT_SHA256}} {{EXPECTED_RESULT}} {{FUEL}} {{MEMORY_PAGES}} {{NODE}} {{SEED}} {{RECEIPTS}} {{CAPABILITY_CONFIG}} {{CAPABILITY_CONFIG_SHA256}} {{LOG}}</plist>")

(def input
  {:node {:name "asher" :host "asher"}
   :user "asher"
   :home "/Users/asher"
   :binary "/tmp/tender-component-host"
   :bundle {:component "/tmp/app.wasm"
            :wit "/tmp/app.wasm.wit"
            :admission "/tmp/app.wasm.admission.edn"
            :provenance "/tmp/app.wasm.provenance.edn"}
   :component-cid "bafkreieuedg6qftafxc66n55kphyi7yehimgytmgittcon5wjol5kup7a4"
   :component-sha256 "9420cde816602dc5ef37bd53cf847f043a186c4d8644e62737b64b97d551ff07"
   :expected-result 6419002
   :budgets {:fuel 512 :memory-pages 16 :deadline-ms 10000}
   :template template})

(deftest rollout-is-loopback-resident-and-secret-free
  (let [plan (deploy/deployment-plan input)
        rendered (deploy/render-plist template input)]
    (is (= "http://127.0.0.1:18901" (:endpoint plan)))
    (is (= 5 (count (:copies plan))))
    (is (every? #(not (re-find #"[0-9a-f]{64}" %))
                (:prepare-commands plan))
        "the receipt seed is generated on-node, not embedded in the plan")
    (is (re-find #"openssl rand -hex 32" (first (:prepare-commands plan))))
    (is (re-find #"com\.murakumo\.kototama-component" (second (:activate-commands plan))))
    (is (re-find #"6419002" rendered))
    (is (re-find #"bafkreieuedg6" rendered))))

(deftest rollout-fails-closed
  (testing "ambient or incomplete identities cannot be smuggled into the plan"
    (doseq [bad [(assoc input :component-sha256 "bad")
                 (assoc input :component-cid "not-a-cid")
                 (assoc-in input [:budgets :fuel] 0)
                 (assoc input :capability-config
                        {:local "/tmp/capabilities.json" :sha256 "bad"})
                 (assoc input :template "<plist/>")]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (deploy/deployment-plan bad))))))

(deftest effectful-rollout-pins-and-copies-the-capability-config
  (let [sha (apply str (repeat 64 "a"))
        configured (assoc input :capability-config
                          {:local "/tmp/capabilities.json" :sha256 sha})
        plan (deploy/deployment-plan configured)
        rendered (deploy/render-plist template configured)]
    (is (= 6 (count (:copies plan))))
    (is (= "/tmp/capabilities.json" (nth (last (:copies plan)) 4)))
    (is (re-find #"/Users/asher/.murakumo/kototama-component/capabilities.json"
                 rendered))
    (is (re-find (re-pattern sha) rendered))))
