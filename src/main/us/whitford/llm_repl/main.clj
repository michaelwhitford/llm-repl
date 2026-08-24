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
   [us.whitford.llm-repl.roster :as roster]))

(defonce ^{:doc "The prompt loop's current session slug — loop-local UI state,
   NOT registry state (attached clients have their own notion of focus)."}
  current*
  (atom :scratch))

(defn use!
  "Point the terminal loop at `slug` (opening it if needed, `opts` forwarded).
   Returns the session's compact index entry."
  ([slug] (use! slug {}))
  ([slug opts]
   (core/open! slug opts)
   (reset! current* slug)
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

(defn -main
  "Entry: nREPL up first (attach works even while a completion blocks the
   terminal), then banner + loop. --headless ≡ nREPL only (park forever)."
  [& args]
  (let [headless? (contains? (set args) "--headless")
        port      (start-nrepl!)]
    (core/open! @current*)
    (banner port)
    (if headless?
      @(promise)
      (do (run-loop)
          (println "bye")
          (System/exit 0)))))
