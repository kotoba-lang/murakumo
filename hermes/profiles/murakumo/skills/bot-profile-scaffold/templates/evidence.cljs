;; evidence.cljs template — propose-only 測定 bot の no_agent 測定 script（nbb）。
;; 判断・計算はこの script が持つ。agent は出力 JSON を読んで報告するだけ。
;; credential を読まない。実行: nbb scripts/evidence.cljs

(require '[clojure.string :as str]
         '["node:child_process" :as cp]
         '["node:fs" :as fs]
         '["node:path" :as path]
         '["node:os" :as os])

;; nbb の require は "node:xxx" 形。'node.child_process は「namespace not found」。
;; babashka.http-client は nbb に無い。js-await も無い — 同期 HTTP は curl 経由にする
;; （非同期 fetch を持ち込むと async 境界が script 全体に波及する）。

(def repo-root (path/join (os/homedir) "github" "com-junkawasaki"))
(def out-dir (path/join (os/homedir) ".hermes" "profiles" "<PROFILE>" "workspace" "findings"))

(def repos
  ["orgs/<org>/<repo-a>"
   "orgs/<org>/<repo-b>"])

(defn sh [cmd cwd]
  (try (str/trim (str (cp/execSync cmd #js {:cwd cwd :timeout 15000})))
       (catch :default e (str "ERR:" (.-message e)))))

(defn repo-probe [rel]
  (let [abs (path/join repo-root rel)]
    (if (fs/existsSync abs)
      {:path rel
       :head (subs (sh "git rev-parse HEAD" abs) 0 12)
       :dirty? (not (str/blank? (sh "git status --porcelain" abs)))
       :has-test? (some #(fs/existsSync (path/join abs %))
                        ["src/test" "test" "worker"])}
      {:path rel :head "NOT-CHECKED-OUT" :dirty? false :has-test? false})))

(defn probe-url [url]
  ;; curl 経由。出力が 3 桁数字なら status、そうでなければ error 文字列
  (let [out (sh (str "curl -sS -o /dev/null -m 10 -w '%{http_code}' " url) (os/homedir))]
    {:url url :status (if (re-matches #"\d{3}" out) (js/parseInt out 10) out)}))

(defn main []
  (let [ts (.toISOString (js/Date.))
        result {:at ts
                :kind "<PROFILE>-evidence"
                :repos (mapv repo-probe repos)
                :live (mapv probe-url ["https://<host>/health"])
                :priority-order ["<priority-1>" "<priority-2>"]}]
    (fs/mkdirSync out-dir #js {:recursive true})
    (fs/writeFileSync (path/join out-dir (str "<PROFILE>-evidence-"
                                              (.slice ts 0 10) ".json"))
                      (js/JSON.stringify (clj->js result) nil 2))
    (println (js/JSON.stringify (clj->js result) nil 2))))

(main)
