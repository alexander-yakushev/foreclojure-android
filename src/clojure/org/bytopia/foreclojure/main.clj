(ns org.bytopia.foreclojure.main
    (:use [neko.activity :only [defactivity set-content-view! *a]]
          [neko.threading :only [on-ui]]))

(defactivity org.bytopia.foreclojure.ProblemGridActivity
  :key :main
  :on-create
  (fn [this bundle]
    (on-ui
      (set-content-view! (*a)
        [:linear-layout {}
         [:text-view {:text "Hello from Clojure!"}]]))))
