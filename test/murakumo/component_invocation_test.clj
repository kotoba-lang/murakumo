(ns murakumo.component-invocation-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [ed25519.core :as ed]
            [murakumo.component-invocation :as invocation])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.security MessageDigest]))

(defn- hex [bytes]
  (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bytes)))

(defn- sha256 [^String value]
  (hex (.digest (MessageDigest/getInstance "SHA-256")
                (.getBytes value "UTF-8"))))

(defn- signed-receipt [seed request-body]
  (let [request-sha (sha256 request-body)
        public-key (hex (ed/pubkey-from-seed seed))
        body (sorted-map
              "ambient-wasi" false
              "export" "advise"
              "format" "kototama.component-invocation-receipt/v1"
              "output" [{"case" "ok" "value" {"text" "bounded"}}]
              "receipt-public-key" public-key
              "request-sha256" request-sha
              "status" "ok")
        payload (json/generate-string body)]
    {"algorithm" "ed25519"
     "body" body
     "payload" payload
     "signature" (hex (ed/sign seed (.getBytes payload "UTF-8")))}))

(defn- with-server [response-fn f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/v1/invoke"
     (reify HttpHandler
       (handle [_ exchange]
         (let [request-body (slurp (.getRequestBody exchange))
               response (.getBytes
                         (json/generate-string (response-fn request-body))
                         "UTF-8")]
           (.sendResponseHeaders exchange 200 (alength response))
           (with-open [output (.getResponseBody exchange)]
             (.write output response))))))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))
              "/v1/invoke"))
      (finally (.stop server 0)))))

(deftest verified-invocation-is-request-bound
  (let [seed (byte-array (range 32))
        public-key (hex (ed/pubkey-from-seed seed))]
    (with-server
      #(signed-receipt seed %)
      (fn [url]
        (let [body (invocation/invoke!
                    url "advise"
                    [{"case" "generate"
                      "value" {"prompt" "facts" "model" "admitted"}}]
                    {:expected-public-key public-key})]
          (is (= "ok" (get body "status")))
          (is (= "bounded" (get-in body ["output" 0 "value" "text"]))))))))

(deftest tampered-receipt-is-rejected
  (let [seed (byte-array (range 32))
        public-key (hex (ed/pubkey-from-seed seed))]
    (with-server
      (fn [request]
        (assoc-in (signed-receipt seed request)
                  ["body" "output" 0 "value" "text"] "tampered"))
      (fn [url]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"not request-bound"
             (invocation/invoke!
              url "advise" []
              {:expected-public-key public-key})))))))

(deftest arbitrary-network-destinations-are-rejected
  (testing "actors cannot turn an invocation client into ambient HTTP"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"literal loopback"
         (invocation/invoke!
          "https://example.com/v1/invoke" "advise" []
          {:expected-public-key (apply str (repeat 64 "0"))})))))
