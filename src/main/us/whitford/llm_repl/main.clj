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

(defn use!
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

(defn- commands
  "The command surface, enumerated (ns-publics ≡ the contract) — what the
   banner prints and what a TUI palette/MCP facade would generate from."
  []
  (->> (merge (ns-publics 'us.whitford.llm-repl.core)
              (select-keys (ns-publics 'us.whitford.llm-repl.main) ['use!]))
       (remove (fn [[_ v]] (:private (meta v))))
       (map key)
       sort))

(defn- banner [nrepl-port]
  (println "llm-repl — the tape is the value; fork is free.")
  (println (str "  model    " (roster/default-model)))
  (println (str "  nREPL    127.0.0.1:" nrepl-port "  (.nrepl-port written — attach any client)"))
  (println (str "  attach   (require '[us.whitford.llm-repl.core :refer :all])"))
  (println (str "  commands " (str/join " " (commands))))
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

(defn- push-event!
  "Append a system line to the TUI's events (bounded) and mark dirty."
  [state line]
  (swap! state (fn [s]
                 (-> s
                     (update :events #(vec (take-last 200 (conj % line))))
                     (assoc :render-dirty true)))))

(defn- ellipsize [s n]
  (let [s (str/replace (str s) #"\s+" " ")]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- result-events
  "A form result → VERY short event lines (the tree pane's footer is ~24
   cols). Events are a one-line INDEX of what happened — the payload lives in
   the tree; arms are sessions, tab to them. ab! ≡ arm count ⊕ error count."
  [res]
  (if (and (map? res) (:repl/variants res))
    (let [vs   (:repl/variants res)
          errs (count (filter :repl/error (vals vs)))]
      [(str "ab! " (:repl/id res) " " (- (count vs) errs) "✓"
            (when (pos? errs) (str " " errs "✗")))])
    [(str "=> " (ellipsize (pr-str res) 60))]))

(defn- tui-submit!
  "The submission dispatch — same grammar as the plain loop, but every branch
   runs on a WORKER thread so the UI stays live (pending marker meanwhile;
   the plain loop blocks here, the TUI does not)."
  [h text]
  (let [state (:state h)]
    (if (str/starts-with? text "(")
      (future
        (let [evs (try (binding [*ns* (find-ns 'us.whitford.llm-repl.main)]
                         (result-events (eval (read-string text))))
                       (catch Throwable t [(str "error: " (ex-message t))]))]
          (doseq [e evs] (push-event! state e))))
      (let [slug (:slug @state)]
        (swap! state assoc :pending slug :render-dirty true)
        (future
          (let [{:repl/keys [error]} (core/eval! slug text)]
            (swap! state assoc :pending nil :render-dirty true)
            (when error (push-event! state (str "error: " error)))))))))

(defn run-tui
  "Boot the TUI surface over core's registry and BLOCK until it stops.
   The add-watch is the multi-client moment: any registry change — an attached
   nREPL client's eval!, a worker completing — flips the dirty flag; the
   ticker repaints within ~33ms. No polling, no push protocol."
  [nrepl-port]
  (let [h* (promise)
        h  (tui/start! {:registry   core/sessions*
                        :slug       @current*
                        :nrepl-port nrepl-port
                        ;; deferred handle: the input thread starts inside
                        ;; start!, before we hold h — close over the promise
                        :on-submit  (fn [text] (tui-submit! @h* text))
                        :on-stop    (fn [] (remove-watch core/sessions* ::tui))})]
    (deliver h* h)
    (reset! tui* h)
    (add-watch core/sessions* ::tui
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
