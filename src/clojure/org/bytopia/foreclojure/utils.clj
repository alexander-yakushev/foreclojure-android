(ns org.bytopia.foreclojure.utils
  (:require [neko.notify :refer [toast]]
            [neko.threading :refer [on-ui]]))

(defmacro long-running-job
  "Runs body in a future, assumes activity instance is bound to `a`."
  [& body]
  `(future
     (on-ui (.setProgressBarIndeterminateVisibility ~'a true))
     (try ~@body
          (catch Exception ex# (on-ui (toast ~'a (str ex#))))
          (finally (on-ui (.setProgressBarIndeterminateVisibility ~'a false))))))
