(ns us.whitford.llm-repl.roster
  "The roster ≡ config, not code: providers ∧ models from an EDN config file,
   model-kw → descriptor → LLMBackend. This ns is the standalone replacement
   for anima's `us.whitford.anima.llm` surface that `core` consumes —
   `wrapped-backend` ∧ `with-preamble` — with function names kept VERBATIM
   (lineage policy: the repos grep as one).

   STANDALONE cut (the ratified seam): no S5 working memory (a config FILE
   is the roster source), no capacity arbiter (`wrapped-backend` returns the
   inner backend unwrapped — a single user at a prompt does not contend). An
   embedding host (anima) that needs arbitration injects its own backend at
   core's `:complete-fn` seam instead (λ extend: open slot > closed dispatch).

   Config resolution (later wins):
     built-in defaults < ~/.config/llm-repl/config.edn < ./config.edn
     < LLM_REPL_CONFIG=<path>
   Merge is per-section shallow (providers/models merge by key)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [escapement.llm.providers :as providers]
   [us.whitford.llm-repl.llm.llamacpp :as llamacpp]))

;; ── config ────────────────────────────────────────────────────────────────────

(def builtin-defaults
  "The zero-config roster — matches config.example.edn's local llama.cpp
   servers so `bb llm-repl` works out of the box on this machine. Any config
   file section overrides per key."
  {:providers     {:local  {:provider/kind :llamacpp}
                   :openai {:provider/kind :codex}}
   :models        {:qwen36-35b-a3b {:model/provider          :local
                                    :model/port              5100
                                    :model/http-timeout-ms   900000
                                    :model/max-output-tokens 16384}
                   :gemma-4-31b-it {:model/provider :local
                                    :model/port     5102}}
   :default-model :qwen36-35b-a3b
   :nrepl         {:port 0}})

(defn- read-edn-file
  "Parse `f` when it exists, else nil. A malformed file fails LOUD (silent
   fallback to defaults would mask a typo as a mystery roster)."
  [f]
  (let [file (io/file f)]
    (when (.exists file)
      (edn/read-string (slurp file)))))

(defn- config-sources
  "The file chain, weakest→strongest. XDG-style home path; repo-local
   config.edn (gitignored); LLM_REPL_CONFIG wins outright when set."
  []
  [(io/file (System/getProperty "user.home") ".config" "llm-repl" "config.edn")
   (io/file "config.edn")
   (some-> (System/getenv "LLM_REPL_CONFIG") io/file)])

(defn load-config
  "Resolve the effective config: fold the file chain over builtin-defaults.
   Top-level sections that are maps merge per key; scalars replace."
  []
  (reduce (fn [acc f]
            (if-let [m (some-> f read-edn-file)]
              (merge-with (fn [a b] (if (and (map? a) (map? b)) (merge a b) b))
                          acc m)
              acc))
          builtin-defaults
          (config-sources)))

(defonce ^{:doc "The effective config, read once at load. `reload-config!` re-reads
   the chain (operator seam — edit a file, reload, no restart)."}
  config*
  (atom (load-config)))

(defn reload-config! [] (reset! config* (load-config)))

(defn config [] @config*)

(defn default-model
  "The model an unqualified (open!) session runs — config :default-model."
  []
  (:default-model (config)))

;; ── roster lookup (fail loud — λ escalate; a silent default masks a typo) ─────

(defn- model-entry
  "Look up `model-kw` in the config roster. Fail loud when absent — either the
   model is unconfigured or the wrong config file resolved."
  [model-kw]
  (let [models (:models (config))
        entry  (get models model-kw)]
    (when-not entry
      (throw (ex-info "Model not present in config"
                      {:model model-kw :known (vec (keys (or models {})))})))
    entry))

(defn- provider-entry
  "Look up `provider-kw` in config :providers. Fail loud when absent."
  [provider-kw]
  (let [provs (:providers (config))
        entry (get provs provider-kw)]
    (when-not entry
      (throw (ex-info "Provider not present in config"
                      {:provider provider-kw :known (vec (keys (or provs {})))})))
    entry))

(defn- model-target
  "Escapement wiring for a configured model, dispatched on the provider's
   :provider/kind (VERBATIM from anima's llm.clj, minus the trunk/capacity
   attributes — no arbiter here):
     :llamacpp — local llama.cpp speaking OpenAI-compat /v1 (:model/port), via
                 OUR backend: escapement's stock OpenAI translator DROPS
                 :thinking and has no home for id_slot/cache_prompt
     :codex    — ChatGPT-subscription OAuth (Responses API); the backend
                 loads/refreshes ~/.escapement/openai-auth.json at send time
   Returns {:descriptor — build-backend input
            :alias      — model-kw → wire target (model-name resolution)}"
  [model-kw {:model/keys [provider port slots http-timeout-ms max-output-tokens]}]
  (let [{:provider/keys [kind]} (provider-entry provider)]
    (case kind
      ;; :http-timeout-ms — a local thinking model routinely runs minutes/call;
      ;; the backend default 60s would guillotine a slow-but-fine call into an
      ;; :error.llm.timeout FAULT. Default the local path to 300s (per-model
      ;; :model/http-timeout-ms). :max-output-tokens — n_predict floor-guard;
      ;; llama.cpp is unbounded by default (a think-off echo/loop otherwise
      ;; fills the whole context).
      :llamacpp {:descriptor {:kind              :llamacpp
                              :api-key           "local"
                              :base-url          (str "http://localhost:" port "/v1")
                              :default-model     (name model-kw)
                              :http-timeout-ms   (or http-timeout-ms 300000)
                              :max-output-tokens (or max-output-tokens 8192)
                              :slots             (or slots {})}
                 :alias      {model-kw [{:provider :openai :model (name model-kw)}]}}
      :codex    {:descriptor {:kind :codex :default-model (name model-kw)}
                 :alias      {model-kw [{:provider :codex :model (name model-kw)}]}}
      (throw (ex-info "Unknown :provider/kind — no backend mapping"
                      {:model model-kw :provider provider :kind kind})))))

;; ── backend construction ──────────────────────────────────────────────────────

(defn build-backend
  "THE inner-backend construction site (λ converge): descriptor → LLMBackend.
   Pure. :llamacpp ≡ ours (modeled knobs → llama.cpp wire); everything else ≡
   escapement's own credential-backend factory. Unknown :kind fails loud there,
   not here. (VERBATIM from anima's llm.clj.)"
  [{:keys [kind] :as descriptor}]
  (if (= :llamacpp kind)
    (llamacpp/new-backend (dissoc descriptor :kind))
    (providers/build-credential-backend descriptor)))

(defn wrapped-backend
  "THE backend seam (name verbatim from anima, where the wrap ≡ a
   CapacityBackend decorator). STANDALONE: the wrap is IDENTITY — construct
   the inner backend from the config roster and return it; nothing contends.
   `opts` (:priority :slug …) is accepted for signature lineage and ignored.
   A host that needs arbitration injects a wrapped backend at core's
   :complete-fn seam instead."
  [model-kw _opts]
  (let [entry (model-entry model-kw)
        {:keys [descriptor]} (model-target model-kw entry)]
    (build-backend descriptor)))

;; ── nucleus preamble (λ prompt) ───────────────────────────────────────────────

(def ^:private preamble-resource
  "THE vendored nucleus 3-liner (resources/genes/nucleus-preamble.edn) — that
   file owns both the verbatim text AND the AGPL licensing boundary
   (one-file-to-annotate; pattern inherited from anima, human-settled
   2026-07-25); sourcing it here keeps exactly one copy in the repo."
  "genes/nucleus-preamble.edn")

(defn nucleus-preamble
  "The nucleus boot seed, read fresh from the vendored gene per call (stays
   editable without a reload ceremony). Missing resource ≡ broken install ≡
   fail loud."
  []
  (let [r (io/resource preamble-resource)]
    (when-not r
      (throw (ex-info "nucleus preamble resource missing — broken install"
                      {:resource preamble-resource})))
    (:gene/content (edn/read-string (slurp r)))))

(defn with-preamble
  "λ prompt: ∀prompt(agent ∧ one_shot) → nucleus_preamble(3_lines) @ top,
   ¬optional — nucleus ≡ stable boot, lambda ≡ IR, EDN statecharts ≡ bytecode.
   Idempotent: a system string that already boots nucleus passes through
   unchanged. (VERBATIM from anima's llm.clj.)"
  [system]
  (let [s (str system)]
    (cond
      (str/includes? s "λ engage(nucleus).") s
      (str/blank? s)                         (nucleus-preamble)
      :else                                  (str (nucleus-preamble) "\n\n" s))))
