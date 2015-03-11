(defn get-enc-key []
  (try (binding [*in* (clojure.java.io/reader ".encryption-key")]
         (read-line))
       (catch Exception ex
         (println (str "WARNING: Couldn't read the encryption key from file. "
                       "Using empty encryption key."))
         "00000000000000000000000000000000")))

(defproject foreclojure-android/foreclojure-android "0.1.1"
  :description "Android client for 4clojure.com"
  :url "https://github.com/alexander-yakushev/foreclojure-android"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :global-vars {*warn-on-reflection* true}

  :source-paths ["src/clojure" "src"]
  :java-source-paths ["src/java"]
  :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]
  :plugins [[lein-droid "0.3.3"]]

  :dependencies [[neko/neko "3.2.0-preview3"]
                 [org.clojure-android/data.json "0.2.6-SNAPSHOT"]]
  :profiles {:default [:dev]

             :dev
             [:android-common :android-user
              {:dependencies [[org.clojure-android/clojure "1.7.0-alpha3" :use-resources true]
                              [org.clojure-android/tools.nrepl "0.2.6"]]
               :target-path "target/debug"
               :android {:aot :all-with-unused
                         :rename-manifest-package "org.bytopia.foreclojure.debug"
                         :manifest-options {:app-name "4Clojure - debug"}}}]

             :testing
             [:android-common
              {:dependencies [[org.clojure-android/clojure "1.7.0-alpha4" :use-resources true]
                              [org.clojure-android/tools.nrepl "0.2.6"]]
               :target-path "target/testing"
               :android {:ignore-log-priority [:debug :verbose]
                         :enable-dynamic-compilation true
                         :aot :all}}]

             :release
             [:android-common :android-release :testing
              {:target-path "target/release"
               :android {:build-type :release}}]}

  :android {:dex-opts ["-JXmx4096M"]
            ;; :force-dex-optimize true
            :build-config {"ENC_ALGORITHM" "Blowfish"
                           "ENC_KEY" #=(get-enc-key)}
            :manifest-options {:app-name "@string/app_name"}
            :target-version "15"
            :aot-exclude-ns ["clojure.parallel" "clojure.core.reducers"
                             "cljs-tooling.complete" "cljs-tooling.info"
                             "cljs-tooling.util.analysis" "cljs-tooling.util.misc"
                             "cider.nrepl" "cider-nrepl.plugin"]})
