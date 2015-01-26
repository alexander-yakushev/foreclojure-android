(ns org.bytopia.foreclojure.main
  (:require clojure.set
            [neko.activity :refer [defactivity set-content-view!]]
            [neko.debug :refer [*a safe-for-ui]]
            [neko.notify :refer [toast]]
            [neko.threading :refer [on-ui]]
            neko.ui.adapters
            [neko.ui :as ui]
            [neko.ui.mapping :refer [defelement]]
            [neko.ui.menu :as menu]
            [neko.ui.traits :as traits]
            [org.bytopia.foreclojure
             [db :as db]
             [api :as api]])
  (:import android.content.Intent
           android.text.Html
           android.widget.GridView
           android.widget.TextView))

(defelement :grid-view
  :classname GridView
  :traits [:on-item-click])

;; (api/login "testclient" "testclient" false)
(defn refresh [a]
  (future
    (when (api/network-connected?)
      (on-ui (.setProgressBarIndeterminateVisibility a true))
      (try
        (let [user "testclient"
              last-id (db/initialize a)
              {:keys [solved-ids new-ids]} (api/fetch-solved-problem-ids last-id)
              locally-solved (db/get-solved-ids-for-user a user)
              diff (clojure.set/difference solved-ids locally-solved)]
          ;; Mark new problems as solved although we don't have the code yet.
          (doseq [problem-id diff]
            (db/update-solution a user problem-id {:is_solved true}))
          ;; Download new problems and insert them into database
          (doseq [problem-id new-ids]
            (when-let [json (assoc (api/fetch-problem problem-id)
                              "id" problem-id)]
              (db/insert-problem a json)))
          (when (pos? (count new-ids))
            (on-ui (toast (format "Downloaded %d new problems."
                                  (count new-ids))))))
        (finally (on-ui (.setProgressBarIndeterminateVisibility a false)))))))

;; (refresh (*a))

(defn launch-problem-activity
  [a problem-id]
  (let [intent (Intent. a (resolve 'org.bytopia.foreclojure.ProblemActivity))]
    (.putExtra intent "problem-id" problem-id)
    (.putExtra intent "user" "testclient")
    (.startActivity a intent)))

(defactivity org.bytopia.foreclojure.ProblemGridActivity
  :key :main
  :on-create
  (fn [this bundle]
    (neko.activity/request-window-features! this :indeterminate-progress)
    (safe-for-ui
     (let [this (*a)]
       (on-ui
         (.addFlags (.getWindow (*a)) android.view.WindowManager$LayoutParams/FLAG_KEEP_SCREEN_ON)
         (refresh this)
         (set-content-view! (*a)
           [:linear-layout {:orientation :vertical}
            [:grid-view {:column-width (traits/to-dimension (*a) [160 :dp])
                         :num-columns :auto-fit
                         :stretch-mode :stretch-spacing-uniform
                         :on-item-click (fn [parent _ position __]
                                          (let [id (-> (.getAdapter parent)
                                                       (.getItem position)
                                                       :problems/_id)]
                                            (launch-problem-activity (*a) id)))
                         :adapter (neko.ui.adapters/ref-adapter
                                   (fn []
                                     [:relative-layout {:layout-height [160 :dp]
                                                        :id-holder true
                                                        :padding [5 :dp]}
                                      [:text-view {:id :title
                                                   :text-size [20 :sp]
                                                   :typeface android.graphics.Typeface/DEFAULT_BOLD}]
                                      [:text-view {:id :desc
                                                   :layout-below :title}]
                                      [:image-view {:id :check-img
                                                    :image #res/drawable :org.bytopia.foreclojure/check-icon
                                                    :layout-width [50 :dp]
                                                    :layout-height [50 :dp]
                                                    :visibility :gone
                                                    :layout-align-parent-bottom true
                                                    :layout-align-parent-right true
                                                    :layout-margin-bottom [5 :dp]
                                                    :layout-margin-right [5 :dp]}]])
                                   (fn [position ^android.view.View view _ data]
                                     (safe-for-ui
                                      (let [{:keys [title desc check-img]} (.getTag view)]
                                        (.setText ^TextView title
                                                  ^String (str (:problems/_id data) ". "
                                                               (:problems/title data)))
                                        (.setText ^TextView desc ^String
                                                  (let [desc-str (str (Html/fromHtml (:problems/description data)))
                                                        lng (count desc-str)]
                                                    (if (> lng 140)
                                                      (str (subs desc-str 0 140) "...") desc-str)))
                                        (.setVisibility ^android.view.View
                                                        check-img (if (:solutions/is_solved data)
                                                                    0 8)))))
                                   (atom (db/all-problems-by-user (*a) "testclient" false)))}]]))))
    ))

(defn ProblemGridActivity-onCreateOptionsMenu [this menu]
  (.superOnCreateOptionsMenu this menu)
  (safe-for-ui
   (let [online? (api/logged-in?)]
     (menu/make-menu
      menu [[:item {:title "Refresh"
                    :icon #res/drawable :android/ic-menu-rotate
                    :show-as-action :always}]
            [:item {:title (format "testclient (%s)"
                                   (if online? "online" "offline"))
                    :icon (if online?
                            #res/drawable :android/ic-menu-share
                            android.R$drawable/ic_menu_help)
                    :show-as-action [:always :with-text]}]])))
  true)
