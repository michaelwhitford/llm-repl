(ns us.whitford.llm-repl.daemon
  "Per-project local daemon lifecycle — the core as a PERSISTENT process the
   TUI attaches to and detaches from.

   The model: the TUI is a transient client; the core is a long-lived headless
   nREPL repl. Two lifecycle owners, cleanly split — a CONTAINER core is
   podman's job (start/stop outside this repo), a LOCAL core is THIS ns's job.
   `bb start`/`bb stop`/`bb status` and the auto-spawn inside `bb llm-repl`
   all route here; NONE of them ever touch a container.

   Per-project, keyed by CWD — like a normal Clojure repl: each project dir
   gets its own daemon, discovered by the `.nrepl-port` it already drops there
   (standard nREPL convention; editors find it too). We co-locate the pid so
   `bb stop` can reach the process:

     <project>/.nrepl-port           port only  (standard; also read by editors)
     <project>/.llm-repl/daemon.edn  {:pid :port :cwd :started-at}
     <project>/.llm-repl/daemon.log  the detached daemon's stdout/stderr

   Detach on macOS (no setsid): `nohup … & echo $!` under /bin/sh — nohup
   ignores SIGHUP, the daemon reparents to launchd, stdio goes to the log, and
   the shell echoes $! so we capture the pid. VERIFIED: survives spawner exit
   AND terminal SIGHUP; SIGTERM stops it. The daemon runs the SAME bb.edn (from
   `babashka.config`) `nrepl` task — the container's plain `bb nrepl` never
   writes daemon.edn, so container and local state never collide."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [java.net Socket InetSocketAddress]))

(set! *warn-on-reflection* true)

;; ── per-project paths ───────────────────────────────────────────────────────────

(defn- state-dir  ^java.io.File [pdir] (io/file pdir ".llm-repl"))
(defn- state-file ^java.io.File [pdir] (io/file (state-dir pdir) "daemon.edn"))
(defn- log-file   ^java.io.File [pdir] (io/file (state-dir pdir) "daemon.log"))
(defn- port-file  ^java.io.File [pdir] (io/file pdir ".nrepl-port"))

(defn project-dir
  "The current project ≡ the launch CWD. Where .nrepl-port, ./config.edn and
   model-written files resolve — the unit a daemon is keyed to."
  []
  (System/getProperty "user.dir"))

;; ── liveness ────────────────────────────────────────────────────────────────────

(defn- pid-alive? [pid]
  (try
    (let [oh (java.lang.ProcessHandle/of (long pid))]
      (and (.isPresent oh) (.isAlive ^java.lang.ProcessHandle (.get oh))))
    (catch Throwable _ false)))

(defn reachable?
  "Can we open a TCP connection to host:port within 300ms? host defaults to
   loopback (the local daemon); the 2-arity serves the :attach remote check."
  ([port] (reachable? "127.0.0.1" port))
  ([host port]
   (try
     (with-open [s (Socket.)]
       (.connect s (InetSocketAddress. ^String host (int port)) 300)
       true)
     (catch Exception _ false))))

(defn alive?
  "A daemon state is alive iff its pid is running AND its port serves — both,
   so a stale pid file with a recycled port (or vice-versa) reads as dead."
  [{:keys [pid port]}]
  (boolean (and pid port (pid-alive? pid) (reachable? port))))

;; ── state file ────────────────────────────────────────────────────────────────
;; Hygiene (audit §3, strictness arc): the spit is temp+rename ATOMIC (a
;; reader can never see a torn write), a corrupt read is NEVER silently ≡
;; absent (it renames aside as evidence ⊕ says so on stderr — the silent
;; version read a torn daemon.edn as "never started" and spawned a SECOND
;; daemon racing the first), and a failed cleanup delete is reported, not
;; dropped (a survivor file resurrects stale state at the next discover).

(defn- move-file!
  "`Files/move` src→dst, atomic ∧ replacing — the rename half of every
   write/aside below. Throws on failure (the caller decides how loud)."
  [^java.io.File src ^java.io.File dst]
  (java.nio.file.Files/move
   (.toPath src) (.toPath dst)
   (into-array java.nio.file.CopyOption
               [java.nio.file.StandardCopyOption/ATOMIC_MOVE
                java.nio.file.StandardCopyOption/REPLACE_EXISTING])))

(defn write-state!
  "Write `st` as `pdir`'s daemon.edn ATOMICALLY: spit to a temp sibling in
   the same directory, then rename into place (`ATOMIC_MOVE` — same-volume
   by construction). A concurrent `read-state` sees the old complete file or
   the new complete file, never a torn one. Spawner-owned (`spawn!` is the
   only production caller); public for the twin suite
   (memories/bb-jvm-private-var-twin-trap)."
  [pdir st]
  (let [tmp (io/file (state-dir pdir) (str "daemon.edn.tmp." (System/nanoTime)))]
    (spit tmp (pr-str st))
    (move-file! tmp (state-file pdir))))

(defn read-state
  "The recorded daemon state for `pdir` (a map), or nil when nothing usable
   is recorded. CORRUPT ≢ ABSENT, never silently: an unparseable (or
   parseable-but-not-a-map) daemon.edn is renamed aside to
   `daemon.edn.corrupt` — evidence preserved for a human — with ONE loud
   stderr line naming both paths, and ONLY THEN treated absent so the next
   spawn gets a clean slate. If even the aside rename fails, the line says
   that too (the file stays; better a repeated loud read than silent loss)."
  [pdir]
  (let [f (state-file pdir)]
    (when (.exists f)
      (let [v (try (edn/read-string (slurp f)) (catch Exception _ ::corrupt))]
        (if (map? v)
          v
          (let [aside (io/file (state-dir pdir) "daemon.edn.corrupt")
                moved? (try (move-file! f aside) true (catch Exception _ false))]
            (binding [*out* *err*]
              (println (str "llm-repl: corrupt daemon state at " (.getPath f)
                            (if moved?
                              (str " — moved aside to " (.getPath aside))
                              " — could NOT be moved aside (file left in place)")
                            "; treating as no daemon recorded")))
            nil))))))

(defn clean-state!
  "Delete `pdir`'s daemon.edn ∧ .nrepl-port. Returns nil when everything
   present was deleted, else `{:failed [path …]}` — and each failure is ONE
   loud stderr line (audit §3: the old version dropped `.delete`'s boolean;
   a survivor file resurrects stale state at the next discover, so failing
   to remove it must never be silent). Public for the twin suite."
  [pdir]
  (let [failed (vec (for [^java.io.File f [(state-file pdir) (port-file pdir)]
                          :when (and (.exists f) (not (.delete f)))]
                      (do (binding [*out* *err*]
                            (println (str "llm-repl: failed to delete " (.getPath f)
                                          " — stale daemon state may be rediscovered")))
                          (.getPath f))))]
    (when (seq failed) {:failed failed})))

(defn read-port-file
  "THE one .nrepl-port parse path (audit §3 unified the former two): the
   file's trimmed content as a port long, or nil when the file is
   absent/blank (≡ no port dropped yet). UNPARSEABLE content throws loud
   with the file ∧ content in ex-data — a .nrepl-port holding garbage is a
   broken environment, not a missing daemon (both former paths threw a bare
   NumberFormatException here; now it names the evidence). Public for the
   twin suite."
  [pdir]
  (let [f (port-file pdir)]
    (when (.exists f)
      (let [s (str/trim (slurp f))]
        (when-not (str/blank? s)
          (try (Long/parseLong s)
               (catch NumberFormatException _
                 (throw (ex-info (str "llm-repl: unparseable .nrepl-port at "
                                      (.getPath f) " — content " (pr-str s)
                                      " is not a port number")
                                 {:file (.getPath f) :content s})))))))))

(defn discover
  "The LIVE daemon state for `pdir`, or nil. A stale record (pid gone or port
   dead) is cleaned and nil returned, so callers spawn fresh."
  [pdir]
  (when-let [st (read-state pdir)]
    (if (alive? st)
      st
      (do (clean-state! pdir) nil))))

(defn attach-target
  "Parse an attach spec (from --attach or roster/attach-spec) to [host port].
   `host:port` or a bare `port`; a BLANK/nil spec falls through to ./.nrepl-port
   in the CWD (the container drops its port into the mounted /work, so attach is
   zero-config from that dir). nil if nothing resolves."
  [spec]
  (if (str/blank? spec)
    (when-let [p (read-port-file (project-dir))]
      ["127.0.0.1" p])
    (if (str/includes? spec ":")
      (let [[h p] (str/split spec #":" 2)] [h (Integer/parseInt p)])
      ["127.0.0.1" (Integer/parseInt spec)])))

;; ── spawn (detached) ────────────────────────────────────────────────────────────

(defn- run-sh
  "Run `cmd` via /bin/sh -c in `dir`, blocking; return its trimmed stdout (the
   last non-blank line — the daemon pid from `echo $!`)."
  [dir cmd]
  (let [pb   (doto (ProcessBuilder. ^java.util.List ["/bin/sh" "-c" cmd])
               (.directory (io/file dir))
               (.redirectErrorStream true))
        proc (.start pb)
        out  (slurp (.getInputStream proc))]
    (.waitFor proc)
    (->> (str/split-lines out) (remove str/blank?) last)))

(defn spawn-cmd
  "The verified nohup incantation that detaches a daemon (see ns docstring),
   built from `cfg-path` ≡ the `babashka.config` system property. THE JVM
   GUARD (D6/D7): under JVM Clojure that property does not exist — no
   convention names bb.edn from a foreign runtime — so this FAILS LOUD with
   instructions instead of the v0.2.0 NPE. The JVM twin attaches to an
   existing daemon; it never spawns one. Public for the twin suite
   (memories/bb-jvm-private-var-twin-trap)."
  [cfg-path pdir]
  (when-not cfg-path
    (throw (ex-info (str "llm-repl: spawning the local daemon requires the bb runtime "
                         "(no `babashka.config` system property under the JVM). "
                         "Start it with `bb start` (or run a headless core with "
                         "`bb nrepl`), then attach — the JVM twin attaches to an "
                         "existing daemon; it never spawns one.")
                    {:runtime :jvm :fix ["bb start" "bb nrepl"]})))
  (str "nohup bb --config " cfg-path
       " --deps-root " (.getParent (io/file cfg-path))
       " nrepl >" (.getPath (log-file pdir)) " 2>&1 & echo $!"))

(defn spawn!
  "Spawn a DETACHED local daemon rooted at `pdir` (its CWD). Reinvokes the SAME
   bb.edn (`babashka.config`; deps-root ≡ its parent) `nrepl` headless task via
   the verified nohup incantation (`spawn-cmd` — fails loud under JVM), captures
   the pid, waits until the nREPL port serves, then WRITES daemon.edn
   (spawner-owned). Returns the live state {:pid :port :cwd :started-at}.
   Throws with the log path if it never becomes ready (or dies) within
   `timeout-ms`."
  ([pdir] (spawn! pdir 20000))
  ([pdir timeout-ms]
   (let [pdir (.getAbsolutePath (io/file pdir))]
     (.mkdirs (state-dir pdir))
     (let [cmd (spawn-cmd (System/getProperty "babashka.config") pdir)
           pid (try (Long/parseLong (run-sh pdir cmd))
                    (catch Exception e
                      (throw (ex-info "daemon spawn failed to yield a pid"
                                      {:pdir pdir :cmd cmd} e))))
           deadline (+ (System/currentTimeMillis) timeout-ms)]
       (loop []
         (let [port (read-port-file pdir)]
           (cond
             (and port (reachable? port))
             (let [st {:pid pid :port port :cwd pdir :started-at (System/currentTimeMillis)}]
               (write-state! pdir st)
               st)

             (not (pid-alive? pid))
             (throw (ex-info "daemon process died during startup"
                             {:pid pid :log (.getPath (log-file pdir))}))

             (> (System/currentTimeMillis) deadline)
             (throw (ex-info "daemon did not become ready in time"
                             {:pid pid :timeout-ms timeout-ms :log (.getPath (log-file pdir))}))

             :else (do (Thread/sleep 100) (recur)))))))))

(defn ensure!
  "A live daemon for `pdir`, spawning one if none is discoverable. Returns
   [state fresh?] — fresh? true when this call spawned it."
  [pdir]
  (if-let [st (discover pdir)]
    [st false]
    [(spawn! pdir) true]))

;; ── stop / status ──────────────────────────────────────────────────────────────

(defn stop!
  "Stop the LOCAL daemon for `pdir`: SIGTERM its recorded pid, clean state.
   Returns the stopped state, or nil if none was recorded. Only ever the pid in
   THIS project's daemon.edn — never a container (podman owns those)."
  [pdir]
  (when-let [{:keys [pid] :as st} (read-state pdir)]
    (when (and pid (pid-alive? pid))
      (try (.destroy ^java.lang.ProcessHandle (.get (java.lang.ProcessHandle/of (long pid))))
           (catch Throwable _ nil)))
    (clean-state! pdir)
    st))

(defn status
  "The daemon status for `pdir`: the state with :alive? and :uptime-ms added,
   or nil when nothing is recorded."
  [pdir]
  (when-let [st (read-state pdir)]
    (assoc st
           :alive?    (alive? st)
           :uptime-ms (when-let [t (:started-at st)] (- (System/currentTimeMillis) t)))))

;; ── CLI (bb start / bb stop / bb status) ─────────────────────────────────────────
;; Print-friendly wrappers for the bb.edn tasks — ONLY the local project daemon,
;; NEVER a container (podman owns those). Kept here so the tasks stay light
;; (no core/tui/client load for a status check).

(defn- fmt-uptime [ms]
  (when ms
    (let [s (quot ms 1000)]
      (cond (< s 60)   (str s "s")
            (< s 3600) (str (quot s 60) "m" (mod s 60) "s")
            :else      (str (quot s 3600) "h" (mod (quot s 60) 60) "m")))))

(defn start-cli
  "`bb start` — spawn (or reuse) this project's local daemon."
  []
  (let [pdir        (project-dir)
        [st fresh?] (ensure! pdir)]
    (println (str "llm-repl daemon " (if fresh? "started" "already running")
                  " — pid " (:pid st) "  port " (:port st) "  cwd " (:cwd st)))))

(defn stop-cli
  "`bb stop` — stop this project's local daemon (never a container)."
  []
  (if-let [st (stop! (project-dir))]
    (println (str "llm-repl daemon stopped — pid " (:pid st)))
    (println "llm-repl — no local daemon recorded for this project")))

(defn- print-remote-status
  "If `:attach` is configured, show the REMOTE (container/host) target and
   whether it's reachable — so `bb status` reflects what `bb llm-repl` would
   actually attach to. Config read lazily (requiring-resolve) so daemon stays a
   low-level ns with no load-time roster dep. NEVER manages the remote — status
   only; podman (or whoever) owns the container's lifecycle."
  []
  (when-let [spec ((requiring-resolve 'us.whitford.llm-repl.roster/attach-spec))]
    (if-let [[host port] (attach-target spec)]
      (println (str "llm-repl remote (:attach) — " host ":" port "  "
                    (if (reachable? host port) "REACHABLE" "UNREACHABLE")))
      (println "llm-repl remote (:attach) — set but unresolved (no host:port and no ./.nrepl-port)"))))

(defn status-cli
  "`bb status` — the local daemon for this project, plus the :attach remote when
   configured (so it reflects what `bb llm-repl` would attach to)."
  []
  (print-remote-status)
  (if-let [s (status (project-dir))]
    (println (str "llm-repl daemon — pid " (:pid s) "  port " (:port s)
                  "  " (if (:alive? s) "ALIVE" "DEAD (stale)")
                  "  up " (fmt-uptime (:uptime-ms s)) "  cwd " (:cwd s)))
    (println "llm-repl — no local daemon for this project (bb start to launch one)")))
