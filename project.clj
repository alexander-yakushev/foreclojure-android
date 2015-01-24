(defproject foreclojure-android/foreclojure-android "0.0.1-SNAPSHOT"
  :description "Android client for 4clojure.com"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :global-vars {*warn-on-reflection* true}

  :source-paths ["src/clojure" "src"]
  :java-source-paths ["src/java"]
  :javac-options ["-target" "1.6" "-source" "1.6" "-Xlint:-options"]
  :plugins [[lein-droid "0.3.0"]]

  :dependencies [[org.clojure-android/clojure "1.7.0-alpha3" :use-resources true]
                 [neko/neko "3.1.0"]
                 [org.clojure/data.json "0.2.5"]]
  :profiles {:default [:dev]

             :dev
             [:android-common :android-user
              {:dependencies [[org.clojure-android/tools.nrepl "0.2.6"]]
               :target-path "target/debug"
               :android {:aot :all-with-unused
                         :rename-manifest-package "org.bytopia.foreclojure.debug"
                         :manifest-options {:app-name "Foreclojure-android - debug"}}}]
             :release
             [:android-common
              {:target-path "target/release"
               :android
               {:ignore-log-priority [:debug :verbose]
                :aot :all
                :build-type :release}}]}

  :android {:dex-opts ["-JXmx4096M"]
            ;; :force-dex-optimize true
            :target-version "18"
            :aot-exclude-ns ["clojure.parallel" "clojure.core.reducers"
                             "cljs-tooling.complete" "cljs-tooling.info"
                             "cljs-tooling.util.analysis" "cljs-tooling.util.misc"
                             "cider.nrepl" "cider-nrepl.plugin"]})
