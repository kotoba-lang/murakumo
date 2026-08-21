(ns murakumo.canonical
  "Deterministic EDN encoding for content identities and signatures.")

(defn value [x]
  (cond
    (map? x) (into (sorted-map) (map (fn [[k v]] [k (value v)])) x)
    (set? x) (vec (sort-by pr-str (map value x)))
    (sequential? x) (mapv value x)
    :else x))

(defn string [x]
  #?(:clj
     (binding [*print-namespace-maps* false
               *print-length* nil
               *print-level* nil
               *print-meta* false
               *print-readably* true]
       (pr-str (value x)))
     :cljs (pr-str (value x))))

(defn encode-bytes [x]
  #?(:clj (.getBytes (string x) java.nio.charset.StandardCharsets/UTF_8)
     :cljs (throw (js/Error. "canonical bytes are JVM-only"))))
