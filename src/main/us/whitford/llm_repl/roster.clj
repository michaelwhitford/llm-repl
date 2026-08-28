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
   [malli.core :as m]
   [malli.error :as me]
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
   ;; the GENERIC default prompt stack — deliberately bland; a machine's own
   ;; boot seed / system voice / orientation belong in its config file
   ;; (D7 RATIFIED: all three layers fully replaceable, uniform chain — an
   ;; embedding host swaps the whole stack, e.g. nucleus lambda notation)
   :preamble      "Be precise and concise. Say when you are unsure. Prefer runnable examples over prose."
   :system-prompt "You are a precise assistant."
   ;; the ENVIRONMENT orientation TEMPLATE appended when :tools is armed —
   ;; the model should know WHERE IT LIVES ({slug} substituted at call time;
   ;; live A/B 2026-08-27: slug interpolation collapses self-location to ONE
   ;; dispatch — see completion/with-tools-system)
   :orientation   (str "Your environment: you are running inside a live Clojure REPL — this "
                       "conversation is a tape held by that process, and you are one of its "
                       "clients. Your clojure_eval tool evaluates code in that same process. "
                       "Use it for any computation or fact about your runtime instead of "
                       "guessing: the repl's answer is ground truth. You are session {slug} "
                       "of that repl — (repl/snapshot {slug}) returns this very "
                       "conversation. To inspect or drive the repl itself: "
                       "(require '[us.whitford.llm-repl :as repl]) then (repl/help) "
                       "lists the session commands — sessions, tapes, forks, and "
                       "N-arm counterfactual fans. (help) is a summary line per "
                       "command; before you drive one, read its full contract with "
                       "(:doc (meta #'repl/CMD)) — the options that matter are "
                       "documented there, not here.")
   :nrepl         {:port 0 :bind "127.0.0.1"}})

;; ── config schema (D7: formal, closed ⊕ :ext) ─────────────────────────────────

(def ^:private prompt-value-schema
  "A prompt-stack layer value: literal string | {:file path} | false
   (explicitly none — stops the chain)."
  [:or :string [:map [:file :string]] [:= false]])

(def config-schema
  "The config contract (D7). CLOSED at the top level — an unknown key is a
   typo caught at load, not a mystery roster; embedding hosts put their own
   keys under :ext (the ratified escape hatch). config.example.edn validates
   against this in CI (step 8)."
  [:map {:closed true}
   [:providers     {:optional true} [:map-of :keyword [:map-of :keyword :any]]]
   [:models        {:optional true} [:map-of :keyword [:map-of :keyword :any]]]
   [:default-model {:optional true} :keyword]
   [:preamble      {:optional true} [:maybe prompt-value-schema]]
   [:system-prompt {:optional true} [:maybe prompt-value-schema]]
   [:orientation   {:optional true} [:maybe prompt-value-schema]]
   [:tools         {:optional true} [:maybe [:or :boolean [:vector :keyword]]]]
   [:nrepl         {:optional true} [:map
                                     [:port {:optional true} :int]
                                     [:bind {:optional true} :string]]]
   [:trace         {:optional true} [:map
                                     [:enabled? {:optional true} :boolean]
                                     [:dir      {:optional true} :string]]]
   [:attach        {:optional true} [:or :string :boolean
                                     [:map
                                      [:host {:optional true} :string]
                                      [:port :int]]]]
   [:ext           {:optional true} [:map-of :keyword :any]]])

(defn validate-config
  "Assert `cfg` against `config-schema`; returns `cfg` unchanged, or throws
   ex-info carrying HUMANIZED errors keyed by path (D7 amendment:
   `{:preamble [\"should be a string\"]}` instead of a mystery roster).
   Public for the twin suite; the ONE caller is load-config."
  [cfg]
  (if (m/validate config-schema cfg)
    cfg
    (throw (ex-info (str "llm-repl config invalid: "
                         (pr-str (me/humanize (m/explain config-schema cfg))))
                    {:errors (me/humanize (m/explain config-schema cfg))}))))

(defn read-edn-file
  "Parse `f` when it exists, else nil. A malformed file fails LOUD, NAMING
   the file (silent fallback to defaults would mask a typo as a mystery
   roster) — and 'malformed' includes TRAILING FORMS (D7 amendment, the
   live 40-minute mystery: a stray `}` closed the top-level map early and
   `edn/read-string` silently read only the FIRST form — the rest of the
   file vanished with no error, and reload couldn't help because disk ≠
   what-was-read). Read ALL forms; more than one ⇒ throw naming what the
   trailing content starts with. Public for the twin suite."
  [f]
  (let [file (io/file f)]
    (when (.exists file)
      (with-open [r (java.io.PushbackReader. (io/reader file))]
        (let [eof   (Object.)
              forms (try
                      (loop [acc []]
                        (let [x (edn/read {:eof eof} r)]
                          (if (identical? x eof) acc (recur (conj acc x)))))
                      (catch Exception e
                        (throw (ex-info (str "llm-repl config unreadable — " (.getPath file)
                                             ": " (ex-message e))
                                        {:file (.getPath file)} e))))]
          (cond
            (empty? forms)      nil
            (= 1 (count forms)) (first forms)
            :else
            (throw (ex-info (str "llm-repl config " (.getPath file) " holds "
                                 (count forms) " top-level forms — exactly one map "
                                 "expected. A stray delimiter probably closed the "
                                 "first form early; trailing content starts with: "
                                 (pr-str (second forms)))
                            {:file (.getPath file)
                             :extra-forms (vec (rest forms))}))))))))

(defn- config-sources
  "The file chain, weakest→strongest. XDG-style home path; repo-local
   config.edn (gitignored); LLM_REPL_CONFIG wins outright when set."
  []
  [(io/file (System/getProperty "user.home") ".config" "llm-repl" "config.edn")
   (io/file "config.edn")
   (some-> (System/getenv "LLM_REPL_CONFIG") io/file)])

(defn load-config
  "Resolve the effective config: fold the file chain over builtin-defaults,
   then VALIDATE the merged result (D7 — fails loud with humanized errors;
   both boot and reload-config! pass through here, so a bad edit can never
   silently land). Top-level sections that are maps merge per key; scalars
   replace."
  []
  (validate-config
   (reduce (fn [acc f]
             (if-let [m (some-> f read-edn-file)]
               (merge-with (fn [a b] (if (and (map? a) (map? b)) (merge a b) b))
                           acc m)
               acc))
           builtin-defaults
           (config-sources))))

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

(defn attach-spec
  "The configured attach target as a normalized spec STRING, or nil when unset
   (≡ local). Config `:attach` may be:
     \"host:port\" / \"port\"   → itself
     {:host \"…\" :port N}      → \"host:port\"
     true                       → \"\" (blank ≡ read ./.nrepl-port at parse time)
     false / absent             → nil
   The string↔[host port] parse lives in daemon/attach-target; this is only the
   config-shape normalization, so main (dispatch) and daemon (status) agree."
  []
  (let [a (:attach (config))]
    (cond
      (string? a) a
      (true? a)   ""
      (map? a)    (str (or (:host a) "127.0.0.1") ":" (:port a))
      :else       nil)))

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
     :llamacpp — llama.cpp speaking OpenAI-compat /v1 (:model/port, and
                 :model/host when it isn't localhost — a LAN box, or
                 host.containers.internal from inside a container), via
                 OUR backend: escapement's stock OpenAI translator DROPS
                 :thinking and has no home for id_slot/cache_prompt
     :codex    — ChatGPT-subscription OAuth (Responses API); the backend
                 loads/refreshes ~/.escapement/openai-auth.json at send time
   Returns {:descriptor — build-backend input
            :alias      — model-kw → wire target (model-name resolution)}"
  [model-kw {:model/keys [provider host port slots http-timeout-ms max-output-tokens]}]
  (let [{:provider/keys [kind]} (provider-entry provider)]
    (case kind
      ;; :http-timeout-ms — a local thinking model routinely runs minutes/call;
      ;; the backend default 60s would guillotine a slow-but-fine call into an
      ;; :error.llm.timeout FAULT. Default the local path to 300s (per-model
      ;; :model/http-timeout-ms). :max-output-tokens — n_predict floor-guard;
      ;; llama.cpp is unbounded by default (a think-off echo/loop otherwise
      ;; fills the whole context). :model/host — default localhost; a
      ;; containerized repl names the host gateway, a LAN box its hostname.
      :llamacpp {:descriptor {:kind              :llamacpp
                              :api-key           "local"
                              :base-url          (str "http://" (or host "localhost") ":" port "/v1")
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

;; ── the prompt stack (λ prompt — generic; NO baked-in prompt text) ────────────
;; Every prompt layer is CONFIG, not architecture (D7 RATIFIED 2026-08-28):
;; :preamble (boot seed) ⊕ :system-prompt (system voice) ⊕ :orientation
;; (environment template) all resolve through ONE inheritance chain and can be
;; replaced wholesale — an embedding host (anima) swaps the entire stack for
;; nucleus lambda-notation prompts. This tool ships only bland generic
;; defaults (builtin-defaults, the bottom of every chain).

(defn- expand-home [path]
  (if (str/starts-with? (str path) "~")
    (str (System/getProperty "user.home") (subs (str path) 1))
    (str path)))

(defn- render-prompt
  "A prompt-layer VALUE → text. string ≡ literal; {:file path} ≡ slurped
   plain text (~ expands — where a host's nucleus lambda-notation prompt
   file lives). Anything else fails loud — a typo'd shape silently dropping
   a prompt layer would be the worst failure mode."
  [layer v]
  (cond
    (string? v)              v
    (and (map? v) (:file v)) (str/trimr (slurp (expand-home (:file v))))
    :else (throw (ex-info (str "Unrenderable " layer " value — want string or {:file path}")
                          {:layer layer :value v}))))

(defn- resolve-chained
  "THE prompt-stack resolver (D7 RATIFIED: one chain, one mental model —
   every layer of the stack resolves identically, so an embedding host can
   replace the WHOLE stack from config). Walk

     session `sk`  >  model `mk`  >  provider `pk`  >  config root `rk`

   First-PRESENT wins (a level REPLACES, never concatenates). Absent key ≡
   inherit upward; present nil ∨ `false` ∨ blank ≡ explicitly NONE (stops
   the chain). Returns the rendered string or nil."
  [{:keys [model] :as session-config} sk mk pk rk]
  (let [cfg  (config)
        m    (get-in cfg [:models model])
        p    (get-in cfg [:providers (:model/provider m)])
        pick (fn [src k] (when (and src (contains? src k)) [(get src k)]))
        [v]  (or (pick session-config sk)
                 (pick m mk)
                 (pick p pk)
                 (pick cfg rk))]
    (when-not (or (nil? v) (false? v) (and (string? v) (str/blank? v)))
      (render-prompt rk v))))

(defn resolve-preamble
  "The boot-seed layer: session :preamble > model :model/preamble >
   provider :provider/preamble > root :preamble. Rendered string or nil."
  [session-config]
  (resolve-chained session-config :preamble :model/preamble :provider/preamble :preamble))

(defn resolve-system-prompt
  "The system-voice layer: session :system (the existing session knob) >
   model :model/system-prompt > provider :provider/system-prompt > root
   :system-prompt. Replaces completion's baked \"You are a precise
   assistant.\" (that text now lives in builtin-defaults, bottom of the
   chain). Rendered string or nil."
  [session-config]
  (resolve-chained session-config :system :model/system-prompt :provider/system-prompt :system-prompt))

(defn resolve-orientation
  "The environment-orientation layer ({slug} template, applied by
   completion/with-tools-system iff :tools rides the wire): session
   :orientation > model :model/orientation > provider :provider/orientation
   > root :orientation. Replaces completion's `tools-system` def (the
   template now lives in builtin-defaults, bottom of the chain). Rendered
   string or nil."
  [session-config]
  (resolve-chained session-config :orientation :model/orientation :provider/orientation :orientation))

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
