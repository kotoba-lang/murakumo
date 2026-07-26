(ns murakumo.component-authority-deploy-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.component-authority-deploy :as deploy]))

(def authority
  {:issuer "did:key:murakumo"
   :trusted-keys
   {"current" {:issuer "did:key:murakumo"
               :public-key-hex (apply str (repeat 64 "a"))}
    "next" {:issuer "did:key:murakumo"
            :public-key-hex (apply str (repeat 64 "b"))}}
   :port 9443
   :path "/v1/component-authority"
   :tls-pkcs12-path "/etc/kototama/component-authority.p12"
   :tls-password-env "KOTOTAMA_AUTHORITY_TLS_PASSWORD"})

(def node {:name "asher" :host "asher" :authority-host "asher.example"})

(deftest node-plan-binds-audience-endpoint-and-overlapping-keys
  (let [plan (deploy/deployment-plan authority node)
        config-command (second (:commands plan))]
    (is (= "kototama://asher" (:audience plan)))
    (is (= "https://asher.example:9443/v1/component-authority"
           (:endpoint plan)))
    (is (= 2 (count (:copies plan))))
    (is (= "../kototama/deploy/bin/kototama-authority-daemon"
           (nth (first (:copies plan)) 4)))
    (is (re-find #"/opt/kototama/deps\.edn"
                 (first (:commands plan))))
    (is (re-find #":audience \"kototama://asher\"" config-command))
    (is (re-find #"\"current\"" config-command))
    (is (re-find #"\"next\"" config-command))
    (is (not (re-find #"PASSWORD=" config-command)))))

(deftest deployment-stops-before-config-when-secret-prerequisite-fails
  (let [plan (deploy/deployment-plan authority node)
        remote-calls (atom [])]
    (is (thrown? clojure.lang.ExceptionInfo
                 (deploy/apply-deployment!
                  plan
                  (constantly {:exit 0})
                  (fn [_ command]
                    (swap! remote-calls conj command)
                    {:exit 1}))))
    (is (= 1 (count @remote-calls)))
    (is (re-find #"component-authority\.secret" (first @remote-calls)))))

(deftest explicit-pinned-kototama-artifact-root-is-used
  (let [plan (deploy/deployment-plan authority node "/build/kototama-v0.4.0")]
    (is (= "/build/kototama-v0.4.0/deploy/bin/kototama-authority-daemon"
           (nth (first (:copies plan)) 4)))))

(deftest invalid-key-rotation-config-fails-closed
  (is (thrown? clojure.lang.ExceptionInfo
               (deploy/deployment-plan
                (assoc-in authority [:trusted-keys "next" :issuer]
                          "did:key:attacker")
                node))))
