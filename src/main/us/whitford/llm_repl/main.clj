(ns us.whitford.llm-repl.main
  "The launcher: ONE process, one registry, N attach surfaces.

     llm-repl
     ├── sessions* registry (core — atom, tapes immutable)
     ├── terminal prompt loop (this ns — the human's surface; TUI later)
     └── nREPL server (background — writes .nrepl-port, editors auto-attach)

   The prompt loop and every attached nREPL client drive the SAME command
   vars (core's ns-publics ≡ the contract — a future TUI palette and MCP
   facade enumerate the same namespace). Tapes are immutable values and the
   registry is an atom, so concurrent surfaces are safe by construction.

   Terminal grammar (minimal increment-1 loop, deliberately dumb):
     (form …)   → eval'd as Clojure with core referred — (open! :x), (fork! …)
     :q         → quit
     bare text  → (eval! <current-session> text) — chat with the tape
   `use!` switches the loop's current session; the prompt shows slug ∧ depth."
  (:require
   [clojure.string :as str]
   [us.whitford.llm-repl.client :as client]
   [us.whitford.llm-repl.daemon :as daemon]
   ;; :refer :all — deliberate: THIS ns is the loop's eval surface; every api
   ;; command must resolve bare in a typed (form). The `core` alias stays for
   ;; main's own code (reads qualified) — full short-name rewire is step 6.
   [us.whitford.llm-repl :as core :refer :all]
   [us.whitford.llm-repl.roster :as roster]
   [us.whitford.llm-repl.tui :as tui]))

(defonce ^{:doc "The prompt loop's current session slug — loop-local UI state,
   NOT registry state (attached clients have their own notion of focus)."}
  current*
  (atom :scratch))

(defonce ^{:doc "The active TUI handle when the TUI surface is up, else nil —
   lets use! (callable from a typed form OR an attached client) retarget the
   TUI's focus too."}
  tui*
  (atom nil))

(defn ^{:manual "Point the prompt/TUI at a session, creating it if needed."} use!
  "Point the human surface at `slug` (opening it if needed, `opts` forwarded)
   — the plain loop's current session AND the TUI's focus when active.
   Returns the session's compact index entry."
  ([slug] (use! slug {}))
  ([slug opts]
   (core/open! slug opts)
   (reset! current* slug)
   (when-let [h @tui*]
     (swap! (:state h) assoc :slug slug :scroll 0 :render-dirty true))
   {:repl/id slug :repl/depth (count (:tape (core/snapshot slug)))}))

;; ── nREPL (the attach surface) ────────────────────────────────────────────────

(defn- bb? [] (some? (System/getProperty "babashka.version")))

(defn start-nrepl!
  "Start the nREPL server on the config port (0 ≡ ephemeral) and bind
   address (default loopback), write .nrepl-port (editor auto-discovery
   convention), return the actual port. bb → babashka.nrepl.server;
   JVM → nrepl.server — resolved at runtime so neither classpath needs the
   other's dep.

   :nrepl {:bind \"0.0.0.0\"} opens the attach surface beyond loopback —
   nREPL is unauthenticated eval; do that only behind a wall (a container
   publishing loopback-only, a firewall). Default stays 127.0.0.1."
  []
  (let [{:keys [port bind] :or {port 0 bind "127.0.0.1"}} (:nrepl (roster/config))
        actual (if (bb?)
                 (let [start! (requiring-resolve 'babashka.nrepl.server/start-server!)
                       server (start! {:host bind :port port :quiet true})]
                   (.getLocalPort ^java.net.ServerSocket (:socket server)))
                 (let [start! (requiring-resolve 'nrepl.server/start-server)
                       server (start! :bind bind :port port)]
                   (:port server)))]
    (spit ".nrepl-port" (str actual))
    actual))

;; ── terminal loop ─────────────────────────────────────────────────────────────

;; this ns carries its own operator command (use!) — register it so the ONE
;; manual (banner ∧ (help) ∧ overlay ∧ future MCP facade) includes it
(core/register-manual-ns! 'us.whitford.llm-repl.main)

(defn- commands
  "The CURATED command surface — (core/manual), the same compile the help
   overlay and (help) render. Plumbing stays out of the banner."
  []
  (map :name (core/manual)))

(defn- banner [nrepl-port]
  (println "llm-repl — the tape is the value; fork is free.")
  (println (str "  model    " (roster/default-model)))
  (println (str "  nREPL    " (get-in (roster/config) [:nrepl :bind] "127.0.0.1") ":" nrepl-port
                "  (.nrepl-port written — attach any client)"))
  (println (str "  attach   (require '[us.whitford.llm-repl :refer :all])"))
  (println (str "  commands " (str/join " " (commands)) "  — (println (help)) for details"))
  (println      "  loop     bare text → eval! on the current session | (form) → clojure | :q → quit"))

(defn- prompt []
  (let [slug  @current*
        depth (count (:tape (core/snapshot slug)))]
    (print (str (name slug) "[" depth "]> "))
    (flush)))

(defn- eval-form
  "Eval a paren line in THIS ns (core referred via the require above — so
   (open! …) (eval! …) (fork! …) (use! …) all resolve). Errors print as data,
   never kill the loop."
  [line]
  (try
    (let [form   (read-string line)
          result (binding [*ns* (find-ns 'us.whitford.llm-repl.main)]
                   (eval form))]
      (prn result))
    (catch Throwable t
      (println (str "error: " (ex-message t))))))

(defn- chat-line
  "Bare text → ONE committed turn on the current session; print the reply
   (or the error — eval! returns error-as-data, never throws)."
  [line]
  (let [{:repl/keys [reply error]} (core/eval! @current* line)]
    (println (or reply (str "error: " error)))))

(defn run-loop
  "The blocking read-eval-print loop. EOF (ctrl-d) or :q exits. Branches via
   `core/parse-submission` (D5, the ONE grammar) — the trim happens here,
   before classifying, so `:text` (≡ the trimmed line) matches this loop's
   pre-grammar behavior byte-for-byte."
  []
  (prompt)
  (loop []
    (when-let [line (read-line)]
      (let [{:keys [kind text]} (core/parse-submission (str/trim line))]
        (case kind
          :quit :done
          :noop (do (prompt) (recur))
          :form (do (eval-form text) (prompt) (recur))
          :chat (do (chat-line text) (prompt) (recur)))))))

;; ── TUI wiring (the wire layer: submissions ⊕ registry watch) ─────────────────

(defn- ellipsize [s n]
  (let [s (str/replace (str s) #"\s+" " ")]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- use-form?
  "True when `text` is a (use! …) form — focus is a LOCAL-surface concern, so
   the wire layer intercepts it (never sends it across a remote attach; the
   container's use! would retarget the container, not this pane)."
  [text]
  (try
    (let [f (read-string text)]
      (and (seq? f) (= 'use! (first f))))
    (catch Throwable _ false)))

(defn- do-use!
  "Handle (use! slug [opts]) LOCALLY: ensure the session exists on the core
   (local or remote), then move THIS surface's focus. Args are literals."
  [client state text]
  (future
    (try
      (let [[_ slug opts] (read-string text)]
        (client/ensure! client slug (or opts {}))
        (reset! current* slug)
        (swap! state assoc :slug slug :scroll 0 :render-dirty true)
        (core/event! (str "use! " slug)))
      (catch Throwable t (core/event! (str "error: " (ex-message t)))))))

(defn- show-help!
  "The TUI help overlay: the client's (help) string rendered OVER the right
   pane (a view swap — the tape is untouched; chrome never enters the tape).
   The wire layer renders because tui stays core-free — content is injected;
   the client fetches it (local ≡ core/help, remote ≡ eval over the wire)."
  [client h]
  (tui/show-overlay! (:state h)
                     {:title "help"    ; frame decorates: ⧉ + esc hint
                      :lines (str/split-lines (client/help-text client))}))

(defn- tui-submit!
  "The submission dispatch — same grammar as the plain loop (`core/parse-
   submission`, D5), but every branch runs on a WORKER thread so the UI stays
   live (pending marker meanwhile; the plain loop blocks here, the TUI does
   not). Everything routes through the `client` seam: local ≡ in-process
   core, remote ≡ nREPL to a container core. Receipts flow through the
   client's events stream — the SAME stream attached clients write. `(help)`
   and `(use! …)` are intercepted (help would echo a useless ellipsis; use!
   is local focus) — LAYERED on top of the grammar's `:form` kind, same
   convention as the plain loop's `:form`→eval-form (D5's docstring: a
   surface layers its own intercepts, it does not re-derive the grammar).
   `:noop`/`:quit` never actually arrive here (edit-step never submits a
   blank buffer, and the TUI has no typed-`:q`-quits-the-app convention — it
   quits via ctrl-c/ctrl-d/esc) — both fall to the `:chat` case unchanged
   from pre-grammar behavior, same as any other bare text. Captured *out*
   pops as an overlay; the VALUE stays a footer receipt (unless it's a
   command receipt itself — :suppress-echo? — so the tree isn't doubled)."
  [client h text]
  (let [state (:state h)
        {:keys [kind]} (core/parse-submission text)]
    (cond
      (and (= kind :form) (= (str/trim text) "(help)"))
      (show-help! client h)

      (and (= kind :form) (use-form? text))
      (do-use! client state text)

      (= kind :form)
      (future
        (let [{:keys [value out err suppress-echo?]} (client/form client text)]
          (cond
            err (core/event! (str "error: " err))
            (not suppress-echo?) (core/event! (str "=> " (ellipsize value 60))))
          (when-not (str/blank? out)
            (tui/show-overlay! state {:title (ellipsize text 28)
                                      :lines (str/split-lines out)}))))

      :else
      (let [slug (:slug @state)]
        (swap! state assoc :pending slug :render-dirty true)
        (future
          ;; prose! emits its own …/✓/✗ receipts at the core seam
          (client/prose! client slug text)
          (swap! state assoc :pending nil :render-dirty true))))))

(defn run-tui
  "Boot the TUI over the CLIENT's registry/events deref-ables and BLOCK until
   it stops. The client's notify! is the multi-client moment: local ≡
   add-watch on sessions*/events*, remote ≡ a poll thread against the
   container — either way a change flips the dirty flag and the ticker
   repaints within ~33ms. The frame never knows which; :registry is just a
   deref-able (core's atom, or a cache atom kept fresh over the wire)."
  [client nrepl-port]
  (let [h* (promise)
        h  (tui/start! {:registry   (client/registry client)
                        :events     (client/events client)
                        :slug       @current*
                        :nrepl-port nrepl-port
                        ;; deferred handle: the input thread starts inside
                        ;; start!, before we hold h — close over the promise
                        :on-submit  (fn [text] (tui-submit! client @h* text))
                        :on-help    (fn [] (show-help! client @h*))
                        :on-stop    (fn [] (client/shutdown! client))})]
    (deliver h* h)
    (reset! tui* h)
    ;; The notify callback is ALSO the attach-loss wake-up (client protocol):
    ;; when the poll loop flips status to :lost we honor the attach contract —
    ;; teardown, say why on the REAL screen (stop! leaves the alt screen), and
    ;; exit nonzero. Never render a dead core as a live one
    ;; (memories/tui-dead-daemon-silent).
    (client/notify! client
                    (fn []
                      (let [{:keys [attach reason]} @(client/status client)]
                        (if (= :lost attach)
                          (do (try (tui/stop! h) (catch Throwable _ nil))
                              (println (str "llm-repl — attach lost: "
                                            (or reason "core unreachable")
                                            " (tapes live with the core; reattach when it returns)"))
                              (System/exit 1))
                          (tui/request-render! (:state h))))))
    @(promise)))

(defn- run-attached
  "Attach the REMOTE TUI to the core at `spec` (the container) and drive it.
   An EXPLICIT attach request — --attach flag OR :attach config — is a
   CONTRACT: if it can't be honored (no target, no TTY, connection refused)
   we FAIL LOUD and exit, never silently start a local repl. Falling back
   would mask a down container as a mystery FRESH session (empty tape, lost
   state) — the worst failure mode. Local is only ever the DEFAULT, chosen
   when no attach is requested at all (roster/attach-spec ≡ nil). Phase-1 is
   TUI-only (the render surface is the whole point)."
  [spec]
  (let [target (daemon/attach-target spec)]
    (cond
      (nil? target)
      (do (println "llm-repl — attach requested but no target resolved (no host:port and no ./.nrepl-port)")
          (System/exit 1))

      (not (tui/interactive-terminal?))
      (do (println "llm-repl — attach requires an interactive terminal (phase-1: TUI only)")
          (System/exit 1))

      :else
      (let [[host port] target
            client      (try
                          (client/remote host port)
                          (catch Exception e
                            (println (str "llm-repl — could not attach to " host ":" port
                                          " (" (.getMessage e) ")"))
                            (System/exit 1)))]
        (reset! current* (or (some-> (client/registry client) deref keys first) :scratch))
        (println (str "llm-repl — attached to " host ":" port))
        (run-tui client port)))))

(defn- run-local
  "The in-process core surface — NOT a daemon, NOT the TUI. :headless ≡ the
   DAEMON body (also what `bb nrepl` and the container run): start nREPL, open
   scratch, park forever. :plain ≡ a bootstrap line loop driving the in-process
   core (debug/no-TTY). The TUI NEVER runs here — it always ATTACHES (local
   daemon or container), so there is no in-process TUI+core path left."
  [mode]
  (let [port (start-nrepl!)]
    (core/open! @current*)
    (case mode
      :headless (do (banner port) @(promise))
      :plain    (do (banner port) (run-loop) (println "bye") (System/exit 0)))))

(defn- run-local-daemon
  "Local default (no :attach): discover-or-spawn THIS project's daemon and
   attach the TUI to it over loopback — the TUI is a pure client of a separate,
   persistent core. Quit ≡ DETACH (client sockets close, the TUI process exits,
   the daemon keeps running; `bb stop` ends it). No TTY → the in-process plain
   loop (a TUI can't render without a terminal)."
  []
  (if (tui/interactive-terminal?)
    (let [pdir        (daemon/project-dir)
          [st fresh?] (daemon/ensure! pdir)
          client      (client/remote "127.0.0.1" (:port st))]
      (reset! current* (or (some-> (client/registry client) deref keys first) :scratch))
      (println (str "llm-repl — attached to local repl (pid " (:pid st) " port " (:port st) ")"
                    (when fresh? " [spawned]") " — `bb stop` to shut it down"))
      (run-tui client (:port st)))
    (run-local :plain)))

(defn -main
  "Entry. Explicit flags first: --headless / --plain are LOCAL surfaces
   (a container's own `bb nrepl` ≡ --headless, so it never auto-attaches to
   itself); --attach forces the remote TUI. With no flag, config `:attach`
   makes `bb llm-repl` auto-attach to the configured host:port. Attach is a
   CONTRACT — an explicit request (flag or config) that can't connect FAILS
   LOUD and exits; local is only the DEFAULT, when no attach is requested."
  [& args]
  (let [argv         (vec args)
        argset       (set args)
        headless?    (contains? argset "--headless")
        plain?       (contains? argset "--plain")
        attach-idx   (.indexOf argv "--attach")
        flag-attach? (>= attach-idx 0)
        flag-arg     (when flag-attach?
                       (let [nxt (get argv (inc attach-idx))]
                         (when (and nxt (not (str/starts-with? nxt "--"))) nxt)))]
    (cond
      headless?    (run-local :headless)
      plain?       (run-local :plain)
      flag-attach? (run-attached (or flag-arg ""))
      :else        (if-let [cfg (roster/attach-spec)]
                     (run-attached cfg)
                     (run-local-daemon)))))
