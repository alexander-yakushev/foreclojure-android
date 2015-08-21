(defn get-enc-key []
  (try (binding [*in* (clojure.java.io/reader ".encryption-key")]
         (read-line))
       (catch Exception ex
         (println (str "WARNING: Couldn't read the encryption key from file. "
                       "Using empty encryption key."))
         "00000000000000000000000000000000")))

(defproject foreclojure-android/foreclojure-android "0.2.0"
  :description "Android client for 4clojure.com"
  :url "https://github.com/alexander-yakushev/foreclojure-android"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :global-vars {*warn-on-reflection* true}

  :source-paths ["src/clojure" "src"]
  :java-source-paths ["src/java"]
  :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]
  :plugins [[lein-droid "0.4.0"]]

  :dependencies [[org.clojure-android/clojure "1.7.0-r2"]
                 [neko/neko "4.0.0-alpha4"]
                 [org.clojure-android/data.json "0.2.6-SNAPSHOT"]
                 [com.android.support/design "22.2.1" :extension "aar"]]
  :profiles {:default [:dev]

             :dev
             [:android-common :android-user
              {:dependencies [[org.clojure/tools.nrepl "0.2.10"]]
               :target-path "target/debug"
               :android {:aot :all-with-unused
                         :rename-manifest-package "org.bytopia.foreclojure.debug"
                         :manifest-options {:app-name "4Clojure - debug"}}}]

             :release
             [:android-common :android-release
              {:target-path "target/release"
               :android {:ignore-log-priority [:debug :verbose]
                         :enable-dynamic-compilation true
                         :aot :all
                         :build-type :release}}]}

  :android {:dex-opts ["-JXmx4096M" "--incremental"]
            :build-config {"ENC_ALGORITHM" "Blowfish"
                           "ENC_KEY" #=(get-enc-key)}
            :manifest-options {:app-name "@string/app_name"}
            :target-version "21"
            :aot-exclude-ns ["clojure.parallel" "clojure.core.reducers"
                             "cljs-tooling.complete" "cljs-tooling.info"
                             "cljs-tooling.util.analysis" "cljs-tooling.util.misc"
                             "cider.nrepl" "cider-nrepl.plugin"]})
