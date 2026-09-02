#!/usr/bin/env nbb
;; Launcher for `murakumo.infer.submit`.
;;
;; The only thing this file adds is the tokenizer. `torch.tokenizer` is
;; portable .cljc but lives in a west sibling, so this launcher needs
;; `../torch/src` on the classpath:
;;
;;   nbb --classpath src:../torch/src scripts/infer-submit.cljs \
;;       --tokens-file ids.edn --target-did did:key:z…
;;   nbb --classpath src:../torch/src scripts/infer-submit.cljs \
;;       --tokenize-with torch --vocab-file qwen38-vocab.edn --prompt "write a haiku"
;;
;; The require is unconditional on purpose. nbb resolves aliases at ANALYSIS
;; time, so wrapping it in a `try` to make torch optional does not work — the
;; whole file then fails to analyse whether or not torch is present (measured
;; 2026-09-02). Keeping it unconditional means a missing sibling fails with
;; "Could not find namespace: torch.tokenizer", which names what is missing.
;;
;; `murakumo.infer.submit` itself requires nothing outside this repository, so
;; the command's logic stays testable with `--classpath src:test` alone.
(require '[murakumo.infer.submit :as submit]
         '[torch.tokenizer :as tokenizer])

(defn -main [& args]
  (submit/main! args
                (fn [vocab prompt]
                  (tokenizer/encode (tokenizer/tokenizer vocab) prompt))
                (fn [vocab tokens]
                  (tokenizer/decode (tokenizer/tokenizer vocab) tokens))))

(apply -main *command-line-args*)
