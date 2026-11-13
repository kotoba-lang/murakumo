"""Small fully hashed public corpus for pipeline/convergence pilot controls.

WikiText-2 is intentionally too small to qualify a useful 1B model. It avoids
downloading FineWeb-Edu's 2.15GB first shard during implementation validation.
The tokenizer is trained on the training split only; no model checkpoint loads.
"""
(import argparse hashlib json pathlib)
(import numpy :as np)
(import pyarrow.parquet :as pq)
(import huggingface_hub [hf_hub_download])
(import tokenizers [Tokenizer models trainers pre_tokenizers decoders])

(setv REVISION "b08601e04326c79dfdd32d625aee71d232d685c3"
      FILES {"train" "e83889baabc497075506f91975be5fac0d45c5290b6b20582c8cd1e853d0c9f7"
             "validation" "204929b7ff9d6184953f867dedb860e40aa69c078fc1e54b3baaa8fb28511c4c"
             "test" "5f1bea067869d04849c0f975a2b29c4ff47d867f484f5010ea5e861eab246d91"})

(defn digest [path]
  (with [f (open path "rb")]
    (.hexdigest (hashlib.file_digest f "sha256"))))

(defn prepare [output [vocab 32768]]
  (setv output (pathlib.Path output))
  (.mkdir output :parents True :exist_ok True)
  (setv splits {} provenance {})
  (for [[split expected] (.items FILES)]
    (setv filename (+ "wikitext-2-raw-v1/" split "-00000-of-00001.parquet")
          source (hf_hub_download "Salesforce/wikitext" filename
                   :repo_type "dataset" :revision REVISION)
          actual (digest source))
    (when (!= actual expected) (raise (ValueError (+ "source SHA-256 mismatch: " split))))
    (setv (get splits split) (lfor x (.to_pylist (get (pq.read_table source :columns ["text"]) "text"))
                                    :if (.strip x) x)
          (get provenance split) {"path" filename "sha256" actual}))
  ;; Deduplicate exact paragraphs across splits without reading evaluation text
  ;; into the tokenizer or gradients. Do not claim semantic/near-duplicate removal.
  (setv seen (set))
  (for [split ["train" "validation" "test"]]
    (setv unique [])
    (for [text (get splits split)]
      (setv key (.hexdigest (hashlib.sha256 (.encode text "utf-8"))))
      (when (not-in key seen) (.append unique text) (.add seen key)))
    (setv (get splits split) unique))
  (setv tok (Tokenizer (models.BPE :unk_token "<unk>")))
  (setv tok.pre_tokenizer (pre_tokenizers.ByteLevel :add_prefix_space False)
        tok.decoder (decoders.ByteLevel))
  (.train_from_iterator tok (get splits "train")
    :trainer (trainers.BpeTrainer :vocab_size vocab :min_frequency 2
               :special_tokens ["<eod>" "<unk>"]
               :initial_alphabet (pre_tokenizers.ByteLevel.alphabet)))
  (setv tokenizer-path (/ output "tokenizer.json"))
  (.save tok (str tokenizer-path))
  (for [split ["train" "validation" "test"]]
    (setv ids [])
    (for [encoding (.encode_batch tok (get splits split))]
      (.extend ids encoding.ids)
      (.append ids 0))
    (setv path (/ output (+ split ".bin")))
    (.tofile (np.array ids :dtype np.uint16) path)
    (.update (get provenance split)
      {"tokens" (len ids) "paragraphs" (len (get splits split))
       "token_file_sha256" (digest path)}))
  (setv manifest {"dataset" "Salesforce/wikitext" "subset" "wikitext-2-raw-v1"
                 "revision" REVISION "license" "cc-by-sa-3.0 / gfdl (dataset card)"
                 "source" "https://huggingface.co/datasets/Salesforce/wikitext"
                 "tokenizer_train_split_only" True
                 "tokenizer_sha256" (digest tokenizer-path)
                 "vocab_size" (.get_vocab_size tok) "dtype" "uint16"
                 "splits" provenance})
  (.write_text (/ output "manifest.json") (json.dumps manifest :indent 2) :encoding "utf-8")
  (print (json.dumps manifest :sort_keys True)))

(when (= __name__ "__main__")
  (setv parser (argparse.ArgumentParser))
  (.add_argument parser "--output" :required True)
  (.add_argument parser "--vocab" :type int :default 32768)
  (setv args (.parse_args parser))
  (prepare args.output args.vocab))
