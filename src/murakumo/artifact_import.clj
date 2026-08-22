(ns murakumo.artifact-import
  "Offline, CID-verifying bootstrap import for coordinator CAS objects."
  (:require [clojure.string :as str]
            [murakumo.artifact-store :as artifact-store]))

(defn import! [root specification]
  (let [[cid path & extra] (str/split specification #"=" 3)]
    (when-not (and cid path (empty? extra) (not (str/blank? path)))
      (throw (ex-info "usage: <cid>=<path>" {:reason :invalid-import-spec})))
    (let [file (java.io.File. path)]
      (when-not (.isFile file)
        (throw (ex-info "murakumo-artifact: import source is not a file"
                        {:reason :invalid-import-source :path path})))
      (artifact-store/put! root cid
                           (java.nio.file.Files/readAllBytes (.toPath file)))
      {:cid cid :path (.getCanonicalPath file)})))

(defn -main [& [root & specifications]]
  (when (or (str/blank? root) (empty? specifications))
    (throw (ex-info
            "usage: clojure -M:artifact-import <cas-root> <cid>=<path>..."
            {:reason :missing-import-arguments})))
  (doseq [specification specifications]
    (println (pr-str (assoc (import! root specification) :imported? true)))))
