;; murakumo MCP server (nbb / stdio). Exposes API-key issuance to an MCP client;
;; same implementation as the `token` CLI (murakumo.apikey), so they cannot drift.
;;
;;   claude mcp add murakumo -- nbb \
;;     --classpath "src:../org-anthropic-mcp/src" \
;;     scripts/mcp-server.cljs
;;
;; Needs MURAKUMO_TOKEN_SECRET in the environment — the same value the gateway
;; verifies with. Without it both tools refuse rather than emitting a token that
;; would fail at the gateway with an indistinguishable 401.
;;
;; Protocol: one JSON-RPC message per line (not Content-Length framing) — what
;; Claude Code's stdio MCP transport reads. Structure mirrors cloud-itonami's
;; scripts/mcp-server.cljs; dispatch is mcp.execute, tools are murakumo.mcp.

(require '[mcp.execute :as execute]
         '[murakumo.mcp :as mmcp]
         '["readline" :as readline])

(def model (mmcp/manifest))
(def ports {:tool (mmcp/tool-port)})

(defn- respond! [resp]
  (when resp
    (.write js/process.stdout (str (js/JSON.stringify (clj->js resp)) "\n"))))

(def rl (.createInterface readline #js {:input js/process.stdin}))

(.on rl "line"
     (fn [line]
       (let [line (.trim line)]
         (when (seq line)
           (try
             (let [req (js->clj (js/JSON.parse line))
                   resp (execute/handle ports model req)]
               ;; notifications (no id) get no reply, per JSON-RPC 2.0
               (when (contains? req "id") (respond! resp)))
             (catch :default e
               (respond! {"jsonrpc" "2.0" "id" nil
                          "error" {"code" -32700
                                   "message" (str "parse error: " (.-message e))}})))))))

(.on js/process.stdin "end" (fn [] (js/process.exit 0)))
