(ns org.bytopia.foreclojure.utils
  (:require [neko.notify :refer [toast]]
            [neko.threading :refer [on-ui]])
  (:import android.app.Activity))

(defmacro long-running-job
  "Runs body in a future, assumes activity instance is bound to `a`."
  [& body]
  (let [asym (with-meta 'a {:tag android.app.Activity})]
    `(future
       (on-ui (.setProgressBarIndeterminateVisibility ~asym true))
       (try ~@body
            (catch Exception ex# (on-ui (toast (str ex#))))
            (finally (on-ui (.setProgressBarIndeterminateVisibility ~asym false)))))))
