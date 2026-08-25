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
   ;; the GENERIC default boot layer — deliberately bland; a machine's own
   ;; boot seed belongs in its config file (chain: see resolve-preamble)
   :preamble      "Be precise and concise. Say when you are unsure. Prefer runnable examples over prose."
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

(defn default-tools
  "The :tools default an unqualified session runs — config root :tools
   (true ≡ every registered tool | [kw …] ≡ whitelist | absent/nil ≡ none).
   Read at core load like :default-model — armed-ness becomes a MACHINE
   fact that survives restarts, not a per-boot ritual; any session still
   overrides per open!/fork! ({:tools nil} disarms)."
  []
  (:tools (config)))

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

;; ── preamble (λ prompt — generic; NO baked-in boot seed) ──────────────────────
;; The preamble is CONFIG, not architecture: a string glued to the top of the
;; system prompt. This tool ships only a bland generic default; a machine's own
;; boot seed (e.g. nucleus) lives in that machine's config file. DIVERGENCE #3
;; from anima (which hardwires its vendored nucleus gene): with-preamble is now
;; (preamble, system), and resolve-preamble walks a config inheritance chain.

(defn- expand-home [path]
  (if (str/starts-with? (str path) "~")
    (str (System/getProperty "user.home") (subs (str path) 1))
    (str path)))

(defn- render-preamble
  "A preamble VALUE → text. string ≡ literal; {:file path} ≡ slurped plain
   text (~ expands). Anything else fails loud — a typo'd shape silently
   dropping the boot layer would be the worst failure mode."
  [v]
  (cond
    (string? v)            v
    (and (map? v) (:file v)) (str/trimr (slurp (expand-home (:file v))))
    :else (throw (ex-info "Unrenderable :preamble value — want string or {:file path}"
                          {:value v}))))

(defn resolve-preamble
  "Resolve the preamble for a SESSION config through the inheritance chain:

     session :preamble  >  model :model/preamble  >  provider :provider/preamble
     >  config top-level :preamble

   First-PRESENT wins (no concatenation — a level REPLACES the boot text).
   Absent key ≡ inherit upward; present `false` ∨ blank ≡ explicitly NONE
   (stops the chain). Returns the rendered string or nil."
  [{:keys [model] :as session-config}]
  (let [cfg   (config)
        m     (get-in cfg [:models model])
        p     (get-in cfg [:providers (:model/provider m)])
        pick  (fn [src k] (when (and src (contains? src k)) [(get src k)]))
        [v]   (or (pick session-config :preamble)
                  (pick m :model/preamble)
                  (pick p :provider/preamble)
                  (pick cfg :preamble))]
    (when-not (or (nil? v) (false? v) (and (string? v) (str/blank? v)))
      (render-preamble v))))

(defn with-preamble
  "λ prompt: glue `preamble` to the top of `system`. GENERIC — no knowledge of
   any particular boot seed. Idempotent (a system already containing the
   preamble passes through); nil/blank preamble ≡ system unchanged (nil when
   both empty — caller treats that as no system at all)."
  [preamble system]
  (let [s (str system)]
    (cond
      (or (nil? preamble) (str/blank? preamble)) (not-empty s)
      (str/includes? s preamble)                 s
      (str/blank? s)                             preamble
      :else                                      (str preamble "\n\n" s))))
