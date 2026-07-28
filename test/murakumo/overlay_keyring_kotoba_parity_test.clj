(ns murakumo.overlay-keyring-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.identity :as identity]
            [murakumo.overlay.keyring :as keyring]))

(def port-source (slurp "kotoba/overlay_keyring_core.kotoba"))
(def export-prefix
  "default-rotation-seconds epoch key-id-input derive-key-input digit-char nat-str")

(defn- kotoba-literal [s]
  (str \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

(defn- compile-i64-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :i64 " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first #"\(:export \[[^\]]+\]\)"
                                   (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(defn- compile-string-cases [cases]
  (let [defs (for [[name body] cases]
               (str "(defn " name " [] :string " body ")"))
        names (map first cases)
        src (-> port-source
                (str/replace-first #"\(:export \[[^\]]+\]\)"
                                   (str "(:export [" export-prefix " " (str/join " " names) "])"))
                (str "\n" (str/join "\n" defs)))
        kir (:kir (compiler/compile-source src :wasm32-kotoba-v1 {}))]
    (into {} (map (fn [n] [n (ir/execute kir (symbol n) [])]) names))))

(deftest epoch-and-preimages-match
  (let [n (compile-i64-cases
           {"d" "(default-rotation-seconds)"
            "e" "(epoch 259200 86400)"
            "e0" "(epoch 0 86400)"})
        s (compile-string-cases
           {"k" (str "(key-id-input " (kotoba-literal "bafyOverlay") " 3)")
            "dk" (str "(derive-key-input " (kotoba-literal "operator-seed") " "
                      (kotoba-literal "bafyOverlay") " 3)")})]
    (is (= keyring/default-rotation-seconds (get n "d")))
    (is (= (keyring/epoch (* 3 86400)) (get n "e")))
    (is (= (keyring/epoch 0) (get n "e0")))
    (is (= (str "bafyOverlay:key:3") (get s "k")))
    (is (= (keyring/key-id "bafyOverlay" 3)
           (subs (identity/sha256-hex (get s "k")) 0 16)))
    (is (= (get-in (keyring/derive-key "operator-seed" "bafyOverlay" 3) [:key])
           (identity/sha256-hex (get s "dk"))))))
