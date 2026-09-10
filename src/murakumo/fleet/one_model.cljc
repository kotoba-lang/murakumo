(ns murakumo.fleet.one-model
  "One node hosts one model. Pure decisions over a node's process list.

  Owner instruction 2026-09-10: the murakumo cloud fleet is to be designed so
  that a node hosts, as a rule, a single model.

  ## What was actually running

  Measured the same day across the six reachable minis, and the rule was broken
  on every one of them:

    node       llama-server  rpc-server  ComfyUI  ollama
    dan             1            1          1       1
    judah           1            1          0       1
    joseph          1            1          1       1
    benjamin        1            1          1       1
    issachar        1            1          1       1
    simeon          1            1          1       1

  Four model planes on one 16 GiB machine. That is not a tidiness complaint:
  measuring the 27B on levi the previous day required stopping the edge server
  (7.9 GB resident), ComfyUI, ollama and the RPC worker before the weights
  would load at all, and a 32k-token context then took the node off the network
  entirely. A node that hosts four models has no model it can actually serve at
  full size, and nothing in the fleet said so.

  ## Why an idle process still counts

  Only llama-server is large at rest — 7.9 GB on issachar, against ComfyUI's
  0.68 GB and an rpc-server and ollama both under 200 MB. Counting only
  resident bytes would call those three free and they are not: each claims
  memory the moment it is asked to work, and the failure that produces is a
  node that looks like it has room right up to the moment two models are busy
  together. So this counts HOSTS, not bytes.

  `rpc-server` counts even though the model it serves belongs to another node.
  It lends this machine's RAM and GPU to gad's distributed ring, which is
  hosting part of a model by any definition that matters to the node's own
  capacity.

  ## What this namespace is not

  It does not decide which model a node should host, and it does not evict
  anything. It answers one question — how many model planes are on this node,
  and which — so that the installer can refuse to add a second and an operator
  can see the ones that are already there."
  (:require [kotoba.lang.text :as str]))

(def planes
  "Process signatures that mean a model is hosted here, and which plane it
  serves. Ordered: the first match wins, so a more specific signature has to
  come before a more general one.

  Matched on the command line rather than the executable name because the
  same binary serves different planes -- `llama-server` is the node's own
  model, `rpc-server` is a shard of somebody else's.

  Matched case-INSENSITIVELY, and each signature is specific enough to be a
  program rather than a word. The first version matched `ComfyUI` exactly,
  which is how gad spells it; the six minis spell it `comfyui/main.py`, so the
  checker reported them as hosting three planes when they host four. An
  under-report is the dangerous direction here — it lets an install proceed
  onto a node that is already full. `comfyui/main.py` and `ollama serve` carry
  the specificity that case-sensitivity was standing in for, without the miss."
  [{:plane :text        :match "llama-server"
    :what "this node's own text model"}
   {:plane :ring-member :match "rpc-server"
    :what "a shard of another node's model, over llama.cpp RPC"}
   {:plane :media       :match "comfyui/main.py"
    :what "image and video checkpoints"}
   {:plane :ad-hoc      :match "ollama serve"
    :what "whatever ollama has pulled; a second text model by another name"}])

(defn classify-line
  "The plane one process line hosts, or nil."
  [line]
  (let [l (str/lower (str line))]
    (some (fn [{:keys [match] :as p}]
            (when (str/includes? l match) (dissoc p :match)))
          planes)))

(defn hosts
  "Every model plane found in `lines`, deduplicated by plane.

  Deduplicated because two llama-server processes for the same model are one
  host with a worker, not two models -- and because the question this answers
  is how many MODELS, which is what the rule is about."
  [lines]
  (->> lines
       (keep classify-line)
       (reduce (fn [acc p] (if (some #(= (:plane %) (:plane p)) acc) acc (conj acc p))) [])
       vec))

(defn verdict
  "How this node stands against the rule.

    :one    exactly one plane — what the fleet is being designed toward
    :none   nothing hosted; a node with capacity and no model
    :many   the rule is broken, and `:extra` names what has to go

  `:none` is deliberately not a failure. A node that hosts nothing is
  available, and reporting it as a violation would push an operator to fill it
  before deciding what it is for."
  [lines]
  (let [found (hosts lines)]
    (case (count found)
      0 {:verdict :none :hosts [] :extra []}
      1 {:verdict :one :hosts found :extra []}
      {:verdict :many :hosts found
       ;; The node's own text model is the one to keep by default: it is the
       ;; plane the fleet enrols and serves from. Everything else is what an
       ;; eviction removes, and naming them here is what makes the removal
       ;; reviewable instead of a guess at the terminal.
       :extra (vec (remove #(= :text (:plane %)) found))})))

(defn keeps
  "Which plane a node should keep, given what it hosts and what it is FOR.

  `wanted` is the plane the operator is installing. When a node already hosts
  that plane, keeping it is a no-op; when it hosts a different one, that other
  one is what has to go. Stated as a function rather than left to the caller
  because `edge install` and a dedicated-model install ask the same question
  and must not answer it two ways."
  [lines wanted]
  (let [found (hosts lines)]
    {:keep (or (first (filter #(= wanted (:plane %)) found))
               {:plane wanted :what "requested, not yet present"})
     :evict (vec (remove #(= wanted (:plane %)) found))}))

(defn report-line [node {:keys [verdict hosts extra]}]
  (str (format "[%-10s] %-5s " node (name verdict))
       (str/join ", " (map #(name (:plane %)) hosts))
       (when (seq extra)
         (str "  -- breaks the rule; extra: "
              (str/join ", " (map #(str (name (:plane %)) " (" (:what %) ")") extra))))))
