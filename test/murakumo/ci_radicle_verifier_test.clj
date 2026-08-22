(ns murakumo.ci-radicle-verifier-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [ed25519.core :as ed]
            [murakumo.ci.radicle-verifier :as verifier]))

(deftest raw-event-signature-is-cryptographic-rid-scoped-and-fresh
  (let [seed (byte-array (repeat 32 (byte 7)))
        signer (ed/did-key-from-seed seed)
        raw (json/generate-string {:rid "rad:zRepo" :commit "abc"
                                   :ref "refs/heads/main" :signer signer
                                   :timestamp 1000})
        signature (str "ed25519=" (ed/hexify (ed/sign seed (.getBytes raw "UTF-8"))))
        verify? (verifier/create {:authorized-signers {"rad:zRepo" #{signer}}
                                  :clock-seconds (constantly 1001)})]
    (is (verify? {:raw raw :signature signature}))
    (is (false? (verify? {:raw (str raw " ") :signature signature})))
    (is (false? ((verifier/create {:authorized-signers {"rad:other" #{signer}}
                                    :clock-seconds (constantly 1001)})
                 {:raw raw :signature signature})))
    (is (false? ((verifier/create {:authorized-signers {"rad:zRepo" #{signer}}
                                    :clock-seconds (constantly 1400)})
                 {:raw raw :signature signature})))))
