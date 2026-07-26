(ns murakumo.component-authority-http-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [murakumo.component-authority :as authority]
            [murakumo.component-authority-http :as http]
            [murakumo.component-authority-store :as store])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.file Files]))

(def signing
  {:seed (byte-array (range 32))
   :key-id "murakumo-2026-01"
   :issuer "did:key:murakumo"
   :audience "did:key:kototama-edge-a"
   :issued-at-ms 1785000000000})

(defn- receiver! [received]
  (let [server (HttpServer/create
                (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/v1/component-authority"
     (reify HttpHandler
       (handle [_ exchange]
         (let [^HttpExchange exchange exchange
               envelope (edn/read-string (slurp (.getRequestBody exchange)))
               _ (reset! received envelope)
               bytes (.getBytes
                      (pr-str {:ok? true
                               :sequence
                               (get-in envelope
                                       [:event :murakumo.component/sequence])})
                      "UTF-8")]
           (.sendResponseHeaders exchange 202 (alength bytes))
           (with-open [output (.getResponseBody exchange)]
             (.write output bytes))
           (.close exchange)))))
    (.start server)
    server))

(deftest durable-outbox-acks-only-after-real-http-delivery
  (let [journal (.toFile
                 (Files/createTempFile
                  "murakumo-authority-http-" ".edn"
                  (make-array java.nio.file.attribute.FileAttribute 0)))
        received (atom nil)
        server (receiver! received)]
    (try
      (let [port (.getPort (.getAddress server))
            [_ event] (authority/revoke
                       (authority/initial-state) "bafyreicomponent")
            envelope (authority/sign-event event signing)]
        (store/enqueue! journal envelope)
        (is (= 1 (count (store/pending journal))))
        (is (= 1
               (store/deliver-pending!
                journal
                (http/publisher
                 (str "http://127.0.0.1:" port
                      "/v1/component-authority")
                 {:allow-insecure-loopback? true}))))
        (is (= envelope @received))
        (is (empty? (store/pending journal))))
      (finally
        (.stop server 0)
        (Files/deleteIfExists (.toPath journal))))))

(deftest insecure-remote-http-is-rejected-before-connect
  (is (thrown? clojure.lang.ExceptionInfo
               (http/publish!
                "http://example.com/v1/component-authority"
                (authority/sign-event
                 (second (authority/revoke
                          (authority/initial-state) "bafyreicomponent"))
                 signing)
                {}))))
