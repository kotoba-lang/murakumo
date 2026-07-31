(ns murakumo.tunnel-kotoba-parity-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [murakumo.tunnel :as tunnel]))

(def port-source (slurp "kotoba/tunnel_core.kotoba"))
(def export-prefix
  (str "default-connect-timeout-s default-control-persist-s rc-marker "
       "digit-char nat-str i64-str wrap-cmd connect-timeout-opt control-path-opt "
       "control-persist-opt scp-dest close-master-control-opt "
       "ensure-forward-command replace-forward-command remote-curl-command "
       "marker-prefix? strip-marker-digits "
       "batch-mode-opt strict-host-key-opt control-master-opt "
       "digit-val? parse-digits trim-ws "
       "pick-exit trim-err err-ws? "
       "ssh-bin scp-bin o-flag O-flag exit-ctl "
       "pgrep-bin pkill-bin f-flag fN-flag L-flag "
       "null-redirect or-sep settle-sleep pkill-suffix "
       "localhost-colon curl-prefix curl-stderr-redirect "
       "forward-spec ssh-forward-prefix"))

(def ^:private scp-ty
  "[:record :tunnel/scp [[:host :string] [:dest :string]]]")

(def ^:private ports-ty
  "[:record :tunnel/ports [[:local-port :i64] [:remote-port :i64]]]")

(def ^:private forward-ty
  "[:record :tunnel/forward [[:local-port :i64] [:remote-port :i64] [:host :string]]]")

(def ^:private exit-ty
  "[:record :tunnel/exit [[:has-rc :bool] [:rc :i64] [:ssh-exit :i64]]]")

(defn- kotoba-literal [s]
  (str \" (-> (str s) (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) \"))

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

(deftest wrap-and-commands-match
  (let [actual (compile-string-cases
                {"m" "(rc-marker)"
                 "w" (str "(wrap-cmd " (kotoba-literal "exit 7") ")")
                 "ct" "(connect-timeout-opt 8)"
                 "cp" (str "(control-path-opt " (kotoba-literal "/tmp/m/%C") ")")
                 "cs" "(control-persist-opt 45)"
                 "sd" (str "(scp-dest (record-new " scp-ty " "
                           (kotoba-literal "asher") " "
                           (kotoba-literal ".murakumo/bin/kotoba") "))")
                 "cm" (str "(close-master-control-opt " (kotoba-literal "/tmp/m/%C") ")")
                 "ef" (str "(ensure-forward-command (record-new " forward-ty " 18099 8077 "
                           (kotoba-literal "asher") "))")
                 "rf" (str "(replace-forward-command (record-new " forward-ty " 18077 8077 "
                           (kotoba-literal "asher") "))")
                 "rc" (str "(remote-curl-command "
                           (kotoba-literal "http://localhost:8077/health") ")")})]
    (is (= tunnel/rc-marker (get actual "m")))
    (is (= (tunnel/wrap-cmd "exit 7") (get actual "w")))
    (is (str/includes? (last (tunnel/ssh-argv "asher" "exit 7")) (get actual "w")))
    (is (= "ConnectTimeout=8" (get actual "ct")))
    (is (= "ControlPath=/tmp/m/%C" (get actual "cp")))
    (is (= "ControlPersist=45s" (get actual "cs")))
    (is (= "asher:.murakumo/bin/kotoba" (get actual "sd")))
    (is (= "ControlPath=/tmp/m/%C" (get actual "cm")))
    (is (= (tunnel/ensure-forward-command 18099 8077 "asher") (get actual "ef")))
    (is (= (tunnel/replace-forward-command 18077 8077 "asher") (get actual "rf")))
    (is (= (tunnel/remote-curl-command "http://localhost:8077/health") (get actual "rc")))))

(deftest constants-and-marker-helpers
  (let [n (compile-i64-cases
           {"t" "(default-connect-timeout-s)"
            "p" "(default-control-persist-s)"
            "mp" (str "(if (marker-prefix? " (kotoba-literal "__murakumo_rc=7") ") 1 0)")
            "mn" (str "(if (marker-prefix? " (kotoba-literal "partial") ") 1 0)")
            "pd7" (str "(parse-digits " (kotoba-literal "7") ")")
            "pd0" (str "(parse-digits " (kotoba-literal "0") ")")
            "pdw" (str "(parse-digits " (kotoba-literal " 42 ") ")")
            "pdx" (str "(parse-digits " (kotoba-literal "x") ")")
            "pde" (str "(parse-digits " (kotoba-literal "") ")")
            "dv1" (str "(if (digit-val? " (kotoba-literal "9") ") 1 0)")
            "dv0" (str "(if (digit-val? " (kotoba-literal "a") ") 1 0)")})
        s (compile-string-cases
           {"d" (str "(strip-marker-digits " (kotoba-literal "__murakumo_rc=7") ")")
            "bm" "(batch-mode-opt)"
            "sh" "(strict-host-key-opt)"
            "cm" "(control-master-opt)"
            "tr" (str "(trim-ws " (kotoba-literal "  hi  ") ")")})]
    (is (= tunnel/default-connect-timeout-s (get n "t")))
    (is (= tunnel/default-control-persist-s (get n "p")))
    (is (= 1 (get n "mp")))
    (is (= 0 (get n "mn")))
    (is (= "7" (get s "d")))
    (is (= 7 (get n "pd7")))
    (is (= 0 (get n "pd0")))
    (is (= 42 (get n "pdw")))
    (is (= -1 (get n "pdx")))
    (is (= -1 (get n "pde")))
    (is (= 1 (get n "dv1")))
    (is (= 0 (get n "dv0")))
    (is (= "BatchMode=yes" (get s "bm")))
    (is (= "StrictHostKeyChecking=accept-new" (get s "sh")))
    (is (= "ControlMaster=auto" (get s "cm")))
    (is (= "hi" (get s "tr")))
    ;; host parse-rc digits path via pure strip+parse
    (let [[_ rc] (tunnel/parse-rc "out\n__murakumo_rc=7\n")]
      (is (= 7 rc))
      (is (= rc (get n "pd7"))))))

(deftest pick-exit-and-trim-err-match
  (let [i (compile-i64-cases
           {"p1" (str "(pick-exit (record-new " exit-ty " true 7 0))")
            "p0" (str "(pick-exit (record-new " exit-ty " false 7 255))")})
        s (compile-string-cases
           {"te" (str "(trim-err " (kotoba-literal " connection refused\n") ")")
            "te0" (str "(trim-err " (kotoba-literal "") ")")})]
    (is (= 7 (get i "p1")))
    (is (= 255 (get i "p0")))
    (is (= "connection refused" (get s "te")))
    (is (= "" (get s "te0")))
    (is (= {:exit 7 :ssh-exit 0 :out "" :err ""}
           (tunnel/sh-result {:exit 0 :out "__murakumo_rc=7\n" :err nil})))
    (is (= {:exit 255 :ssh-exit 255 :out "" :err "connection refused"}
           (tunnel/sh-result {:exit 255 :out "" :err " connection refused\n"})))
    (is (= {:exit 1 :err "missing"}
           (tunnel/scp-result {:exit 1 :err " missing\n"})))))

(deftest ssh-scp-argv-fragments-match
  (let [s (compile-string-cases
           {"ssh" "(ssh-bin)"
            "scp" "(scp-bin)"
            "o" "(o-flag)"
            "O" "(O-flag)"
            "ex" "(exit-ctl)"})]
    (is (= tunnel/ssh-bin (get s "ssh")))
    (is (= tunnel/scp-bin (get s "scp")))
    (is (= tunnel/o-flag (get s "o")))
    (is (= tunnel/O-flag (get s "O")))
    (is (= tunnel/exit-ctl (get s "ex")))
    (is (= "ssh" (get s "ssh")))
    (is (= "scp" (get s "scp")))
    (is (= "-o" (get s "o")))
    (is (= "-O" (get s "O")))
    (is (= "exit" (get s "ex")))
    (is (= tunnel/ssh-bin (first (tunnel/ssh-argv "asher" "true" {:wrap? false}))))
    (is (= tunnel/scp-bin (first (tunnel/scp-argv "asher" "a" "b"))))
    (is (= [tunnel/ssh-bin tunnel/o-flag "ControlPath=/tmp/m/%C"
            tunnel/O-flag tunnel/exit-ctl "asher"]
           (tunnel/close-master-argv "asher" "/tmp/m/%C")))
    (is (= tunnel/o-flag (first (tunnel/conn-opts nil))))))

(deftest forward-curl-fragments-match
  (let [s (compile-string-cases
           {"pg" "(pgrep-bin)"
            "pk" "(pkill-bin)"
            "f" "(f-flag)"
            "fN" "(fN-flag)"
            "L" "(L-flag)"
            "nr" "(null-redirect)"
            "ors" "(or-sep)"
            "sl" "(settle-sleep)"
            "ps" "(pkill-suffix)"
            "lc" "(localhost-colon)"
            "cp" "(curl-prefix)"
            "cr" "(curl-stderr-redirect)"
            "fs" (str "(forward-spec (record-new " ports-ty " 18099 8077))")
            "sp" "(ssh-forward-prefix)"
            "ef" (str "(ensure-forward-command (record-new " forward-ty " 18099 8077 "
                      (kotoba-literal "asher") "))")
            "rf" (str "(replace-forward-command (record-new " forward-ty " 18099 8077 "
                      (kotoba-literal "asher") "))")
            "rc" (str "(remote-curl-command "
                      (kotoba-literal "http://localhost:8077/health") ")")})]
    (is (= tunnel/pgrep-bin (get s "pg")))
    (is (= tunnel/pkill-bin (get s "pk")))
    (is (= tunnel/f-flag (get s "f")))
    (is (= tunnel/fN-flag (get s "fN")))
    (is (= tunnel/L-flag (get s "L")))
    (is (= tunnel/null-redirect (get s "nr")))
    (is (= tunnel/or-sep (get s "ors")))
    (is (= tunnel/settle-sleep (get s "sl")))
    (is (= tunnel/pkill-suffix (get s "ps")))
    (is (= tunnel/localhost-colon (get s "lc")))
    (is (= tunnel/curl-prefix (get s "cp")))
    (is (= tunnel/curl-stderr-redirect (get s "cr")))
    (is (= "18099:localhost:8077" (get s "fs")))
    (is (str/starts-with? (get s "sp") "ssh -o "))
    (is (str/includes? (get s "sp") "-fN -L "))
    (is (= (tunnel/ensure-forward-command 18099 8077 "asher") (get s "ef")))
    (is (= (tunnel/replace-forward-command 18099 8077 "asher") (get s "rf")))
    (is (= (tunnel/remote-curl-command "http://localhost:8077/health") (get s "rc")))
    (is (str/starts-with? (get s "ef") "pgrep -f '18099:localhost:8077 asher'"))
    (is (str/includes? (get s "ef") "|| ssh -o BatchMode=yes -fN -L 18099:localhost:8077 asher"))
    (is (str/starts-with? (get s "rf") "pkill -f '18099:localhost'"))
    (is (str/includes? (get s "rf") "sleep 0.3; ssh -o BatchMode=yes -fN -L "))
    (is (= "curl -s -m 5 http://localhost:8077/health 2>/dev/null" (get s "rc")))))
