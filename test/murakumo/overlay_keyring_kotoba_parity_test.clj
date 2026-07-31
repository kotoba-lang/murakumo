(ns murakumo.overlay-keyring-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.identity :as identity]
            [murakumo.overlay.keyring :as keyring]))

(def port-source (slurp "kotoba/overlay_keyring_core.kotoba"))

(def ^:private epoch-in-ty
  "[:record :keyring/epoch-in [[:seconds :i64] [:rotation-seconds :i64]]]")

(def ^:private key-id-in-ty
  "[:record :keyring/key-id-in [[:overlay :string] [:epoch :i64]]]")

(def ^:private derive-in-ty
  "[:record :keyring/derive-in [[:operator-seed :string] [:overlay :string] [:epoch :i64]]]")
(def export-prefix
  (str "default-rotation-seconds epoch key-id-input derive-key-input "
       "digit-char nat-str i64-str seed-sep key-id-mid derive-key-mid "
       "key-id-hex-len type-key type-rotation"))

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
            "e" (str "(epoch (record-new " epoch-in-ty " 259200 86400))")
            "e0" (str "(epoch (record-new " epoch-in-ty " 0 86400))")})
        s (compile-string-cases
           {"k" (str "(key-id-input (record-new " key-id-in-ty " "
                     (kotoba-literal "bafyOverlay") " 3))")
            "dk" (str "(derive-key-input (record-new " derive-in-ty " "
                      (kotoba-literal "operator-seed") " "
                      (kotoba-literal "bafyOverlay") " 3))")})]
    (is (= keyring/default-rotation-seconds (get n "d")))
    (is (= (keyring/epoch (* 3 86400)) (get n "e")))
    (is (= (keyring/epoch 0) (get n "e0")))
    (is (= (str "bafyOverlay" keyring/key-id-mid "3") (get s "k")))
    (is (= (str "bafyOverlay:key:3") (get s "k")))
    (is (= (keyring/key-id "bafyOverlay" 3)
           (subs (identity/sha256-hex (get s "k")) 0 16)))
    (is (= (get-in (keyring/derive-key "operator-seed" "bafyOverlay" 3) [:key])
           (identity/sha256-hex (get s "dk"))))))

(deftest keyring-seps-tokens-match
  (let [s (compile-string-cases
           {"ss" "(seed-sep)"
            "km" "(key-id-mid)"
            "dm" "(derive-key-mid)"
            "tk" "(type-key)"
            "tr" "(type-rotation)"
            "ki" (str "(key-id-input (record-new " key-id-in-ty " "
                      (kotoba-literal "ov") " 2))")
            "di" (str "(derive-key-input (record-new " derive-in-ty " "
                      (kotoba-literal "seed") " "
                      (kotoba-literal "ov") " 2))")})
        n (compile-i64-cases
           {"hl" "(key-id-hex-len)"
            "dr" "(default-rotation-seconds)"})]
    (is (= keyring/seed-sep (get s "ss")))
    (is (= ":" (get s "ss")))
    (is (= keyring/key-id-mid (get s "km")))
    (is (= ":key:" (get s "km")))
    (is (= keyring/derive-key-mid (get s "dm")))
    (is (= ":murakumo-overlay-key:" (get s "dm")))
    (is (= keyring/type-key (get s "tk")))
    (is (= "murakumo.overlay.key" (get s "tk")))
    (is (= keyring/type-rotation (get s "tr")))
    (is (= "murakumo.overlay.key-rotation" (get s "tr")))
    (is (= keyring/key-id-hex-len (get n "hl")))
    (is (= 16 (get n "hl")))
    (is (= keyring/default-rotation-seconds (get n "dr")))
    (is (= (str "ov" keyring/key-id-mid "2") (get s "ki")))
    (is (= (str "seed" keyring/seed-sep "ov" keyring/derive-key-mid "2")
           (get s "di")))
    (is (= keyring/type-key (:type (keyring/derive-key "s" "o" 0))))
    (is (= keyring/type-rotation
           (:type (keyring/rotation-plan "s" "o" 0))))))
