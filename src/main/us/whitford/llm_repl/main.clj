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
   ;; :refer :all — deliberate: THIS ns is the loop's eval surface; every core
   ;; command must resolve bare in a typed (form). The alias stays for main's
   ;; own code (reads qualified).
   [us.whitford.llm-repl.core :as core :refer :all]
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
  "Start the nREPL server on the config port (0 ≡ ephemeral), write
   .nrepl-port (editor auto-discovery convention), return the actual port.
   bb → babashka.nrepl.server; JVM → nrepl.server — resolved at runtime so
   neither classpath needs the other's dep."
  []
  (let [port (get-in (roster/config) [:nrepl :port] 0)
        actual (if (bb?)
                 (let [start! (requiring-resolve 'babashka.nrepl.server/start-server!)
                       server (start! {:host "127.0.0.1" :port port :quiet true})]
                   (.getLocalPort ^java.net.ServerSocket (:socket server)))
                 (let [start! (requiring-resolve 'nrepl.server/start-server)
                       server (start! :bind "127.0.0.1" :port port)]
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
  (println (str "  nREPL    127.0.0.1:" nrepl-port "  (.nrepl-port written — attach any client)"))
  (println (str "  attach   (require '[us.whitford.llm-repl.core :refer :all])"))
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
  "The blocking read-eval-print loop. EOF (ctrl-d) or :q exits."
  []
  (prompt)
  (loop []
    (when-let [line (read-line)]
      (let [line (str/trim line)]
        (cond
          (= line ":q")             :done
          (str/blank? line)         (do (prompt) (recur))
          (str/starts-with? line "(") (do (eval-form line) (prompt) (recur))
          :else                     (do (chat-line line) (prompt) (recur)))))))

;; ── TUI wiring (the wire layer: submissions ⊕ registry watch) ─────────────────

(defn- ellipsize [s n]
  (let [s (str/replace (str s) #"\s+" " ")]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- form-echo!
  "Echo a form's VALUE onto core's receipt stream — but ONLY when core didn't
   already emit a receipt at the command seam (command returns carry :repl/id;
   their receipts come from core). Payloads live in the tree; arms are
   sessions, tab to them."
  [res]
  (when-not (and (map? res) (:repl/id res))
    (core/event! (str "=> " (ellipsize (pr-str res) 60)))))

(defn- show-help!
  "The TUI help overlay: (core/help) rendered OVER the right pane (a view
   swap — the tape is untouched; chrome never enters the tape). The wire
   layer renders because tui stays core-free — content is injected."
  [h]
  (tui/show-overlay! (:state h)
                     {:title "help · esc dismisses"
                      :lines (str/split-lines (core/help))}))

(defn- tui-submit!
  "The submission dispatch — same grammar as the plain loop, but every branch
   runs on a WORKER thread so the UI stays live (pending marker meanwhile;
   the plain loop blocks here, the TUI does not). Receipts flow through
   core/events* — the SAME stream attached clients write — never a TUI-local
   side channel (equal clients in the chrome layer too). `(help)` is
   intercepted to the overlay (evaluating it would echo a 60-char ellipsis —
   useless; nREPL clients call core/help directly and get the string)."
  [h text]
  (let [state (:state h)]
    (cond
      (= (str/trim text) "(help)")
      (show-help! h)

      (str/starts-with? text "(")
      (future
        (try (binding [*ns* (find-ns 'us.whitford.llm-repl.main)]
               (form-echo! (eval (read-string text))))
             (catch Throwable t (core/event! (str "error: " (ex-message t))))))

      :else
      (let [slug (:slug @state)]
        (swap! state assoc :pending slug :render-dirty true)
        (future
          ;; eval! emits its own …/✓/✗ receipts at the seam
          (core/eval! slug text)
          (swap! state assoc :pending nil :render-dirty true))))))

(defn run-tui
  "Boot the TUI surface over core's registry and BLOCK until it stops.
   The add-watches are the multi-client moment: any registry change (an
   attached nREPL client's eval!, a worker completing) OR any receipt on
   core/events* (a tapeless trampoline!/bounce! leaving its trace) flips the
   dirty flag; the ticker repaints within ~33ms. No polling, no push
   protocol."
  [nrepl-port]
  (let [h* (promise)
        h  (tui/start! {:registry   core/sessions*
                        :events     core/events*
                        :slug       @current*
                        :nrepl-port nrepl-port
                        ;; deferred handle: the input thread starts inside
                        ;; start!, before we hold h — close over the promise
                        :on-submit  (fn [text] (tui-submit! @h* text))
                        :on-help    (fn [] (show-help! @h*))
                        :on-stop    (fn []
                                      (remove-watch core/sessions* ::tui)
                                      (remove-watch core/events* ::tui))})]
    (deliver h* h)
    (reset! tui* h)
    ;; TWO watches, one repaint path: tape changes AND receipts — an attached
    ;; client's trampoline! never touches the registry, but its receipts land
    (add-watch core/sessions* ::tui
               (fn [_ _ _ _] (tui/request-render! (:state h))))
    (add-watch core/events* ::tui
               (fn [_ _ _ _] (tui/request-render! (:state h))))
    @(promise)))

(defn -main
  "Entry: nREPL up first (attach works even while a completion is in flight),
   then the surface: TUI on an interactive terminal, --plain for the line
   loop, --headless for nREPL only (park forever)."
  [& args]
  (let [args      (set args)
        headless? (contains? args "--headless")
        plain?    (contains? args "--plain")
        port      (start-nrepl!)]
    (core/open! @current*)
    (cond
      headless?
      (do (banner port) @(promise))

      (and (not plain?) (tui/interactive-terminal?))
      (run-tui port)

      :else
      (do (banner port)
          (run-loop)
          (println "bye")
          (System/exit 0)))))
