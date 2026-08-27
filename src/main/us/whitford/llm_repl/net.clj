(ns us.whitford.llm-repl.net
  "A tiny nREPL CLIENT — the wire the remote TUI attaches over.

   The TUI is the last surface that ran IN-PROCESS with the core; every other
   client (editors, models) already attached over nREPL. This ns lets the TUI
   attach too, completing the equal-clients thesis: humans, models, editors —
   and now the TUI itself — all drive the SAME core over the SAME wire.

   Built on `bencode.core`, which is the ONE codec both runtimes share: bundled
   in babashka, an explicit dep (nrepl/bencode) on the JVM — NOT transitive
   from nrepl/nrepl, whose own codec is the internal nrepl.bencode ns (the
   twin suite caught that gap the first time a test loaded this ns under the
   JVM). So — unlike start-nrepl! — there is NO bb?/JVM branch here: one
   implementation, both runtimes.

   nREPL ≡ bencode maps over a socket. A request carries a unique \"id\";
   responses arrive as one-or-more frames tagged with that id, terminated by a
   frame whose \"status\" contains \"done\". eval-msg blocks, gathering frames
   into a single result map — the request/response shape callers want; the
   long-poll tail (phase 2) will layer on the same socket."
  (:require
   [bencode.core :as bc])
  (:import
   [java.io PushbackInputStream BufferedOutputStream EOFException]
   [java.net Socket InetSocketAddress]))

(set! *warn-on-reflection* true)

;; ── decode ────────────────────────────────────────────────────────────────────

(defn- bytes->str [x]
  (cond
    (bytes? x)      (String. ^bytes x "UTF-8")
    (map? x)        (reduce-kv (fn [m k v] (assoc m (bytes->str k) (bytes->str v))) {} x)
    (sequential? x) (mapv bytes->str x)
    :else           x))

;; ── connection ────────────────────────────────────────────────────────────────

(defn connect
  "Open an nREPL connection to `host`:`port` (default 5s connect timeout).
   Returns a conn map {:socket :in :out :ids} — :ids an atom counter for
   per-request message ids. Callers close with `close`."
  ([host port] (connect host port 5000))
  ([host port timeout-ms]
   (let [sock (Socket.)]
     (.connect sock (InetSocketAddress. ^String host (int port)) (int timeout-ms))
     {:socket sock
      :in     (PushbackInputStream. (.getInputStream sock))
      :out    (BufferedOutputStream. (.getOutputStream sock))
      :ids    (atom 0)})))

(defn close [{:keys [^Socket socket]}]
  (when (and socket (not (.isClosed socket)))
    (.close socket)))

(defn connected? [{:keys [^Socket socket]}]
  (and socket (not (.isClosed socket)) (.isConnected socket)))

;; ── request / response ──────────────────────────────────────────────────────────

(defn- send-msg! [{:keys [out]} msg]
  (bc/write-bencode out msg)
  (.flush ^BufferedOutputStream out))

(defn- read-frame [{:keys [in]}]
  (try
    (bytes->str (bc/read-bencode in))
    (catch EOFException _ nil)))

(defn- merge-frame
  "Fold one response frame into the accumulating result. nREPL streams :out and
   :err in pieces (concatenate); :value can arrive more than once (collect a
   vector); :status accumulates as a set; :ns/:ex/:new-session are last-wins."
  [acc {:strs [out err value ns ex root-ex status new-session] :as _frame}]
  (cond-> acc
    out         (update :out str out)
    err         (update :err str err)
    value       (update :value (fnil conj []) value)
    ns          (assoc :ns ns)
    ex          (assoc :ex ex)
    root-ex     (assoc :root-ex root-ex)
    new-session (assoc :session new-session)
    status      (update :status into (set status))))

(defn- request!
  "Send `msg` (an op map) with a fresh id, gather frames tagged with that id
   until 'done'. Returns the merged result. Frames for OTHER ids (async
   completions on a shared session) are ignored. nil frame ≡ EOF → surfaced as
   {:status #{\"eof\"}}."
  [conn msg]
  (let [id  (str (swap! (:ids conn) inc))
        msg (assoc msg "id" id)]
    (send-msg! conn msg)
    (loop [acc {:status #{}}]
      (let [frame (read-frame conn)]
        (cond
          (nil? frame)               (update acc :status conj "eof")
          (not= id (get frame "id")) (recur acc)   ; another id's frame — skip
          :else
          (let [acc (merge-frame acc frame)]
            (if (contains? (:status acc) "done")
              acc
              (recur acc))))))))

(defn clone-session
  "Ask the server for a fresh session id (isolates *out*/*ns* per client).
   Returns the session string, or nil."
  [conn]
  (:session (request! conn {"op" "clone"})))

(defn eval-msg
  "Evaluate `code` on the server, blocking until 'done'. Returns
   {:value <vector-of-printed-values> :out <str> :err <str> :ns <str>
    :status <set> :ex <str?>}. Optional `session` pins evaluation to a cloned
   session (stable *ns*, isolated stream)."
  ([conn code] (eval-msg conn code nil))
  ([conn code session]
   (request! conn (cond-> {"op" "eval" "code" code}
                    session (assoc "session" session)))))

(defn ok?
  "True when an eval-msg result completed cleanly (no error status)."
  [result]
  (let [s (:status result)]
    (and (contains? s "done")
         (not (contains? s "error"))
         (not (contains? s "eof"))
         (nil? (:ex result)))))

(defn value
  "The single printed value string from an eval result (last-wins), or nil."
  [result]
  (last (:value result)))
