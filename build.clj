(ns build
  "Build tasks for llm-repl (tools.build) — copied from fulcro-rad-datalevin,
   then adapted (the ratified model, design § build ∧ release ∧ CI).

   Usage:
     clojure -T:build clean      ; remove target/
     clojure -T:build jar        ; write pom + build thin jar
     clojure -T:build install    ; install to local ~/.m2

   The jar is a THIN SOURCE jar — runtime-neutral, bb and JVM consumers
   alike (bb loads the same .clj sources from the classpath).

   Deploy to Clojars is a SEPARATE step (deps-deploy in an isolated
   classpath — it cannot share tools.build's classpath due to conflicting
   maven libs):
     clojure -T:build jar
     clojure -X:deploy

   Deploying requires Clojars credentials in the environment:
     CLOJARS_USERNAME  — your Clojars username
     CLOJARS_PASSWORD  — a Clojars deploy token (not your account password)

   Versioning policy: -alpha / -beta suffixes are for LOCAL builds only
   (anima rides :local/root or a git sha during alpha); CI (release.yml)
   deploys only full releases (v0.3.0) and release candidates (v0.3.0-RC1),
   setting the VERSION env var from the pushed git tag. From the first RC
   on, a RELEASE is the change boundary (escapement 1.0.1 lesson —
   library-contract hardens there)."
  (:require
    [clojure.tools.build.api :as b]))

(def lib 'us.whitford/llm-repl)
;; Version defaults to the value below for local builds; CI (release.yml) sets
;; the VERSION env var from the pushed git tag so the jar/pom match the release.
(def version (or (System/getenv "VERSION") "0.3.0-alpha"))
(def class-dir "target/classes")
;; Version-less jar name so the :deploy alias needn't track the version
;; (the pom inside the jar carries the coordinates).
(def jar-file (format "target/%s.jar" (name lib)))
(def scm-url "https://github.com/michaelwhitford/llm-repl")

(defn- basis
  "Project basis from the root :deps only (no aliases) — keeps the published
   pom free of build/test-only deps."
  []
  (b/create-basis {:project "deps.edn"}))

(defn clean
  "Remove the target/ build directory."
  [_]
  (b/delete {:path "target"}))

(defn jar
  "Write the pom and build a thin source jar into target/."
  [_]
  (clean nil)
  (b/write-pom
    {:class-dir class-dir
     :lib       lib
     :version   version
     :basis     (basis)
     :src-dirs  ["src/main"]
     :scm       {:url                 scm-url
                 :connection          "scm:git:git://github.com/michaelwhitford/llm-repl.git"
                 :developerConnection "scm:git:ssh://git@github.com/michaelwhitford/llm-repl.git"
                 :tag                 (str "v" version)}
     :pom-data  [[:description "An LLM chat completion as a branchable continuation — the tape is the value; fork is free. A persistent repl for conversation tapes: humans (TUI), models (self-eval), and editors are equal nREPL clients."]
                 [:url scm-url]
                 [:licenses
                  [:license
                   [:name "MIT License"]
                   [:url "https://opensource.org/licenses/MIT"]]]]})
  (b/copy-dir {:src-dirs   ["src/main" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  (println "Built" jar-file))

(defn install
  "Build the jar and install it into the local Maven repository (~/.m2) —
   the anima :local/root alternative once alpha iteration settles."
  [_]
  (jar nil)
  (b/install {:basis     (basis)
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir})
  (println "Installed" (str lib) version "to local Maven repo"))
