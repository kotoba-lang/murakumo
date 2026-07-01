;; murakumo.tunnel-test — offline tests for SSH tunnel command shapes.

(ns murakumo.tunnel-test
  (:require [clojure.test :refer [deftest is]]
            [murakumo.tunnel :as tunnel]))

(deftest tunnel-commands-are-stable
  (is (= ["ssh" "-o" "BatchMode=yes" "-o" "ConnectTimeout=8" "-o" "StrictHostKeyChecking=accept-new" "asher" "true"]
         (tunnel/ssh-argv "asher" "true")))
  (is (= ["scp" "-o" "BatchMode=yes" "-o" "ConnectTimeout=8" "-o" "StrictHostKeyChecking=accept-new"
          "bin/kotoba" "asher:.murakumo/bin/kotoba"]
         (tunnel/scp-argv "asher" "bin/kotoba" ".murakumo/bin/kotoba")))
  (is (= "pgrep -f '18099:localhost:8077 asher' >/dev/null 2>&1 || ssh -o BatchMode=yes -fN -L 18099:localhost:8077 asher"
         (tunnel/ensure-forward-command 18099 8077 "asher")))
  (is (= "pkill -f '18077:localhost' 2>/dev/null; sleep 0.3; ssh -o BatchMode=yes -fN -L 18077:localhost:8077 asher"
         (tunnel/replace-forward-command 18077 8077 "asher")))
  (is (= "curl -s -m 5 http://localhost:8077/health 2>/dev/null"
         (tunnel/remote-curl-command "http://localhost:8077/health")))
  (is (= {:exit 0 :out "ok" :err ""}
         (tunnel/sh-result {:exit 0 :out " ok\n" :err nil})))
  (is (= {:exit 1 :err "missing"}
         (tunnel/scp-result {:exit 1 :err " missing\n"}))))
