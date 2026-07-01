;; murakumo.overlay.cert — kagi-compatible local QUIC certificate material store.

(ns murakumo.overlay.cert
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io StringWriter]
           [java.math BigInteger]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermission]
           [java.security KeyPairGenerator MessageDigest SecureRandom Security]
           [java.security.cert X509Certificate]
           [java.time Instant]
           [java.time.temporal ChronoUnit]
           [java.util Base64 Date EnumSet]
           [org.bouncycastle.asn1.x500 X500Name]
           [org.bouncycastle.asn1.x509 Extension GeneralName GeneralNames]
           [org.bouncycastle.cert.jcajce JcaX509CertificateConverter JcaX509v3CertificateBuilder]
           [org.bouncycastle.jce.provider BouncyCastleProvider]
           [org.bouncycastle.openssl.jcajce JcaPEMWriter]
           [org.bouncycastle.operator.jcajce JcaContentSignerBuilder]))

(def default-store-dir ".murakumo/kagi/quic")
(def default-days 90)
(def index-file "index.edn")

(defn ensure-provider! []
  (when-not (Security/getProvider "BC")
    (Security/addProvider (BouncyCastleProvider.))))

(defn safe-name [s]
  (-> (str s)
      (str/replace #"[^A-Za-z0-9_.-]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn store-dir []
  (io/file (or (System/getenv "MURAKUMO_KAGI_DIR") default-store-dir)))

(defn index-path []
  (io/file (store-dir) index-file))

(defn read-index []
  (let [file (index-path)]
    (if (.exists file)
      (read-string (slurp file))
      {:type "murakumo.overlay.quic-material-index"
       :version 1
       :materials {}
       :audit []})))

(defn write-index! [index]
  (let [file (index-path)]
    (.mkdirs (.getParentFile file))
    (spit file (pr-str index))
    file))

(defn material-name [request]
  (let [overlay (or (get-in request [:session :overlay]) "overlay")
        node (or (get-in request [:session :node])
                 (get-in request [:session :name])
                 "node")
        host (or (get-in request [:connect :host]) "localhost")]
    (safe-name (str overlay "-" node "-" host))))

(defn material-key [request]
  (keyword (material-name request)))

(defn material-paths
  ([request] (material-paths request nil))
  ([request generation]
  (let [dir (store-dir)
        name (cond-> (material-name request)
               generation (str "-g" generation))]
    {:dir dir
     :cert (io/file dir (str name ".cert.pem"))
     :key (io/file dir (str name ".key.pem"))})))

(defn sha256-hex [bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) digest))))

(defn canonical [x]
  (cond
    (map? x) (into (sorted-map) (map (fn [[k v]] [k (canonical v)]) x))
    (sequential? x) (mapv canonical x)
    :else x))

(defn hash-event [event]
  (sha256-hex (.getBytes (pr-str (canonical (dissoc event :hash))) "UTF-8")))

(defn append-audit [idx op material-key record extra]
  (let [prev (last (:audit idx))
        event (merge {:seq (inc (count (:audit idx)))
                      :ts (str (Instant/now))
                      :op op
                      :material material-key
                      :generation (:generation record)
                      :fingerprint (:fingerprint record)
                      :prev-hash (:hash prev)}
                     extra)
        event (assoc event :hash (hash-event event))]
    (update idx :audit (fnil conj []) event)))

(defn file-sha256 [file]
  (when (.exists (io/file file))
    (sha256-hex (Files/readAllBytes (.toPath (io/file file))))))

(defn pem-string [object]
  (let [writer (StringWriter.)]
    (with-open [pem (JcaPEMWriter. writer)]
      (.writeObject pem object))
    (str writer)))

(defn pkcs8-private-key-pem [private-key]
  (let [encoded (.encodeToString (Base64/getMimeEncoder 64 (.getBytes "\n" "UTF-8"))
                                 (.getEncoded private-key))]
    (str "-----BEGIN PRIVATE KEY-----\n"
         encoded
         "\n-----END PRIVATE KEY-----\n")))

(defn ip-address? [host]
  (boolean (re-matches #"[0-9a-fA-F:.]+" (str host))))

(defn subject-alt-names [host]
  (let [names [(GeneralName. (if (ip-address? host)
                               GeneralName/iPAddress
                               GeneralName/dNSName)
                             (str host))]
        localhost? (#{"localhost" "127.0.0.1"} (str host))]
    (GeneralNames. (into-array GeneralName
                               (cond-> names
                                 localhost? (conj (GeneralName. GeneralName/dNSName "localhost"))
                                 localhost? (conj (GeneralName. GeneralName/iPAddress "127.0.0.1")))))))

(defn issue-self-signed!
  ([request] (issue-self-signed! request default-days))
  ([request days]
   (ensure-provider!)
   (let [host (or (get-in request [:connect :host]) "localhost")
         now (Instant/now)
         not-before (Date/from (.minus now 5 ChronoUnit/MINUTES))
         not-after (Date/from (.plus now days ChronoUnit/DAYS))
         kp (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                                (.initialize 2048)))
         subject (X500Name. (str "CN=" host ", O=murakumo.cloud"))
         serial (BigInteger. 160 (SecureRandom.))
         builder (doto (JcaX509v3CertificateBuilder.
                        subject serial not-before not-after subject (.getPublic kp))
                   (.addExtension Extension/subjectAlternativeName
                                  false
                                  (subject-alt-names host)))
         signer (-> (JcaContentSignerBuilder. "SHA256withRSA")
                    (.setProvider "BC")
                    (.build (.getPrivate kp)))
         cert-holder (.build builder signer)
         cert ^X509Certificate (-> (JcaX509CertificateConverter.)
                                   (.setProvider "BC")
                                   (.getCertificate cert-holder))]
     {:cert cert
      :private-key (.getPrivate kp)
     :cert-pem (pem-string cert)
     :key-pem (pkcs8-private-key-pem (.getPrivate kp))
      :fingerprint (sha256-hex (.getEncoded cert))
      :host host
      :not-after not-after})))

(defn write-private! [file text]
  (.mkdirs (.getParentFile file))
  (spit file text)
  (try
    (Files/setPosixFilePermissions (.toPath file)
                                   (EnumSet/of PosixFilePermission/OWNER_READ
                                               PosixFilePermission/OWNER_WRITE))
    (catch UnsupportedOperationException _ nil))
  file)

(defn existing-material? [{:keys [cert key]}]
  (and (.exists cert) (.exists key)))

(defn next-generation [entry]
  (inc (or (:active entry) 0)))

(defn material-record [request generation paths issued]
  {:generation generation
   :overlay (get-in request [:session :overlay])
   :node (get-in request [:session :node])
   :name (get-in request [:session :name])
   :host (get-in request [:connect :host])
   :cert (.getPath (:cert paths))
   :key (.getPath (:key paths))
   :fingerprint (:fingerprint issued)
   :cert-pem-sha256 (sha256-hex (.getBytes (:cert-pem issued) "UTF-8"))
   :key-pem-sha256 (sha256-hex (.getBytes (:key-pem issued) "UTF-8"))
   :not-after (str (.toInstant ^Date (:not-after issued)))
   :issued-at (str (Instant/now))})

(defn active-record [request]
  (let [idx (read-index)
        k (material-key request)
        entry (get-in idx [:materials k])
        generation (:active entry)]
    (when generation
      (get-in entry [:generations generation]))))

(defn active-paths [request]
  (when-let [record (active-record request)]
    {:cert (io/file (:cert record))
     :key (io/file (:key record))
     :record record
     :issued? false}))

(defn ensure-quic-material! [request]
  (if-let [{:keys [cert key] :as paths} (active-paths request)]
    (if (existing-material? paths)
      paths
      (do
        (write-index! (update-in (read-index) [:materials] dissoc (material-key request)))
        (ensure-quic-material! request)))
    (let [idx (read-index)
          k (material-key request)
          entry (get-in idx [:materials k])
          generation (next-generation entry)
          paths (material-paths request generation)
          {:keys [cert-pem key-pem] :as issued} (issue-self-signed! request)
          record (material-record request generation paths issued)]
      (write-private! (:cert paths) cert-pem)
      (write-private! (:key paths) key-pem)
      (write-index! (-> idx
                        (assoc-in [:materials k]
                                  {:active generation
                                   :generations (assoc (:generations entry) generation record)})
                        (append-audit :issue k record {:active generation})))
      (assoc paths :issued? true :record record))))

(defn rotate-quic-material! [request]
  (let [idx (read-index)
        k (material-key request)
        entry (get-in idx [:materials k])
        generation (next-generation entry)
        paths (material-paths request generation)
        {:keys [cert-pem key-pem] :as issued} (issue-self-signed! request)
        record (material-record request generation paths issued)]
    (write-private! (:cert paths) cert-pem)
    (write-private! (:key paths) key-pem)
    (write-index! (-> idx
                      (assoc-in [:materials k]
                                {:active generation
                                 :generations (assoc (:generations entry) generation record)})
                      (append-audit :rotate k record {:active generation})))
    (assoc paths :issued? true :rotated? true :record record)))

(defn ensure-lines [request]
  (let [{:keys [cert key issued? record]} (ensure-quic-material! request)]
    [(pr-str {:ok? true
              :type "murakumo.overlay.quic-material"
              :issued? issued?
              :cert (.getPath cert)
              :key (.getPath key)
              :record record})]))

(defn rotate-lines [request]
  (let [{:keys [cert key record]} (rotate-quic-material! request)]
    [(pr-str {:ok? true
              :type "murakumo.overlay.quic-material"
              :rotated? true
              :cert (.getPath cert)
              :key (.getPath key)
              :record record})]))

(defn list-lines []
  [(pr-str (read-index))])

(defn record-status [record active?]
  (let [cert-file (io/file (:cert record))
        key-file (io/file (:key record))
        cert-ok? (and (.exists cert-file)
                      (= (:cert-pem-sha256 record)
                         (file-sha256 cert-file)))
        key-ok? (and (.exists key-file)
                     (= (:key-pem-sha256 record)
                        (file-sha256 key-file)))]
    (assoc record
           :active? (boolean active?)
           :cert-ok? (boolean cert-ok?)
           :key-ok? (boolean key-ok?)
           :ok? (boolean (and cert-ok? key-ok?)))))

(defn verify-index []
  (let [idx (read-index)
        audit-ok? (loop [prev nil
                         events (:audit idx)]
                    (if-let [event (first events)]
                      (and (= (:prev-hash event) (:hash prev))
                           (= (:hash event) (hash-event event))
                           (recur event (rest events)))
                      true))
        materials (into {}
                        (map (fn [[k entry]]
                               (let [active (:active entry)
                                     generations (into {}
                                                       (map (fn [[g record]]
                                                              [g (record-status record (= g active))])
                                                            (:generations entry)))]
                                 [k (assoc entry
                                           :generations generations
                                           :ok? (and (contains? generations active)
                                                     (every? :ok? (vals generations))))]))
                             (:materials idx)))
        ok? (and audit-ok? (every? :ok? (vals materials)))]
    (assoc idx
           :type "murakumo.overlay.quic-material-verify"
           :ok? (boolean ok?)
           :audit-ok? (boolean audit-ok?)
           :materials materials)))

(defn verify-lines []
  [(pr-str (verify-index))])

(defn delete-file! [path]
  (when path
    (Files/deleteIfExists (.toPath (io/file path)))))

(defn prune-entry [entry keep]
  (let [active (:active entry)
        generations (:generations entry)
        retained (->> generations
                      keys
                      sort
                      reverse
                      (take keep)
                      set
                      (#(conj % active)))
        removed (remove retained (keys generations))]
    (doseq [g removed
            :let [record (get generations g)]]
      (delete-file! (:cert record))
      (delete-file! (:key record)))
    {:entry (assoc entry :generations (select-keys generations retained))
     :removed removed}))

(defn prune-index! [keep]
  (let [idx (read-index)
        pruned (into {}
                     (map (fn [[k entry]]
                            [k (prune-entry entry keep)])
                          (:materials idx)))
        next-index (assoc idx :materials (into {}
                                               (map (fn [[k {:keys [entry]}]]
                                                      [k entry])
                                                    pruned)))
        removed (into {}
                      (map (fn [[k {:keys [removed]}]]
                             [k (vec removed)])
                           pruned))
        audited-index (append-audit next-index
                                    :prune
                                    :*
                                    {:generation nil :fingerprint nil}
                                    {:keep keep :removed removed})]
    (write-index! audited-index)
    {:ok? true
     :type "murakumo.overlay.quic-material-prune"
     :keep keep
     :removed removed
     :index audited-index}))

(defn prune-lines [keep]
  [(pr-str (prune-index! keep))])

(defn parse-argv [args]
  (reduce (fn [m arg]
            (cond
              (= arg "ensure") (assoc m :command :ensure)
              (= arg "list") (assoc m :command :list)
              (= arg "rotate") (assoc m :command :rotate)
              (= arg "verify") (assoc m :command :verify)
              (= arg "prune") (assoc m :command :prune)
              (str/starts-with? arg "--keep=") (assoc m :keep (Long/parseLong (subs arg 7)))
              (str/starts-with? arg "--overlay=") (assoc m :overlay (subs arg 10))
              (str/starts-with? arg "--node=") (assoc m :node (subs arg 7))
              (str/starts-with? arg "--name=") (assoc m :name (subs arg 7))
              (str/starts-with? arg "--host=") (assoc m :host (subs arg 7))
              (str/starts-with? arg "--port=") (assoc m :port (Long/parseLong (subs arg 7)))
              :else m))
          {:overlay "murakumo"
           :node "local"
           :name "local"
           :host "localhost"
           :port 4001
           :keep 2
           :command :ensure}
          args))

(defn request-from-opts [{:keys [overlay node name host port]}]
  {:type "murakumo.overlay.adapter-request"
   :version 1
   :action :serve
   :transport :quic
   :connect {:endpoint :direct
             :kind :quic
             :transport "quic"
             :host host
             :port port
             :overlay overlay
             :node node
             :name name}
   :session {:type "murakumo.overlay.session"
             :overlay overlay
             :node node
             :name name}})

(defn -main [& args]
  (let [{:keys [command] :as opts} (parse-argv args)
        lines (case command
                :list (list-lines)
                :rotate (rotate-lines (request-from-opts opts))
                :verify (verify-lines)
                :prune (prune-lines (:keep opts))
                :ensure (ensure-lines (request-from-opts opts)))]
    (doseq [line lines]
      (println line))))
