(ns org.bytopia.foreclojure.main
  (:require clojure.set
            [neko.activity :refer [defactivity set-content-view!]]
            [neko.debug :refer [*a safe-for-ui]]
            [neko.find-view :refer [find-view find-views]]
            [neko.notify :refer [toast]]
            [neko.threading :refer [on-ui]]
            neko.ui.adapters
            [neko.ui :as ui]
            [neko.ui.mapping :refer [defelement]]
            [neko.ui.menu :as menu]
            [neko.ui.traits :as traits]
            [org.bytopia.foreclojure
             [db :as db]
             [api :as api]
             [utils :refer [long-running-job]]])
  (:import android.content.Intent
           android.text.Html
           android.view.View
           [android.widget CursorAdapter GridView TextView]))

(defelement :grid-view
  :classname GridView
  :traits [:on-item-click])

(defn refresh-ui [a]
  (doto (.getAdapter (find-view a ::problems-gv))
    (.changeCursor (db/get-problems-cursor a "testclient" true))
    (.notifyDataSetChanged)))

;; (on-ui (refresh-ui (*a)))

(defn reload-from-server [a]
  (long-running-job
   (when (api/network-connected?)
     (let [user "testclient"
           last-id (db/initialize a)
           {:keys [solved-ids new-ids]} (api/fetch-solved-problem-ids last-id)
           locally-solved (db/get-solved-ids-for-user a user)
           diff (clojure.set/difference solved-ids locally-solved)]
       ;; Mark new problems as solved although we don't have the code yet.
       (doseq [problem-id diff]
         (db/update-solution a user problem-id {:is_solved true, :code nil}))
       ;; Download new problems and insert them into database
       (doseq [problem-id new-ids]
         (when-let [json (assoc (api/fetch-problem problem-id)
                           "id" problem-id)]
           (db/insert-problem a json)))
       (when (pos? (count new-ids))
         (on-ui (toast (format "Downloaded %d new problems."
                               (count new-ids))))))
     (on-ui (refresh a)))))

;; (reload-from-server (*a))

(defn launch-problem-activity
  [a problem-id]
  (let [intent (Intent. a (resolve 'org.bytopia.foreclojure.ProblemActivity))]
    (.putExtra intent "problem-id" problem-id)
    (.putExtra intent "user" "testclient")
    (.startActivity a intent)))

(defn make-problem-adapter [a]
  (proxy [CursorAdapter] [a nil]
    (newView [context cursor parent]
      (safe-for-ui
       (ui/make-ui-element
        a
        [:relative-layout {:layout-height [160 :dp]
                           :id-holder true
                           :padding [5 :dp]}
         [:text-view {:id ::title-tv
                      :text-size [20 :sp]
                      :typeface android.graphics.Typeface/DEFAULT_BOLD}]
         [:text-view {:id ::desc-tv
                      :layout-below ::title-tv}]
         [:image-view {:id ::done-iv
                       :image #res/drawable :org.bytopia.foreclojure/check-icon
                       :layout-width [50 :dp]
                       :layout-height [50 :dp]
                       :visibility :gone
                       :layout-align-parent-bottom true
                       :layout-align-parent-right true
                       :layout-margin-bottom [5 :dp]
                       :layout-margin-right [5 :dp]}]]
        {:container-type :abs-listview-layout})))
    (bindView [view context cursor]
      (safe-for-ui
       (let [data (neko.data.sqlite/entity-from-cursor
                   cursor
                   [[:problems/_id Integer] [:problems/title String]
                    [:problems/description String] [:solutions/is_solved
                                                    Boolean]])
             [title desc done] (find-views view ::title-tv ::desc-tv ::done-iv)]
         (safe-for-ui
          (.setText ^TextView title
                    ^String (str (:problems/_id data) ". "
                                 (:problems/title data)))
          (.setText ^TextView desc ^String
                    (let [desc-str (str (Html/fromHtml (:problems/description data)))
                          lng (count desc-str)]
                      (if (> lng 140)
                        (str (subs desc-str 0 140) "...") desc-str)))
          (.setVisibility ^android.view.View
                          done (if (:solutions/is_solved data)
                                 View/VISIBLE View/GONE))))))
    (getItem [position]
      (safe-for-ui
       (neko.data.sqlite/entity-from-cursor
        (proxy-super getItem position)
        [[:problems/_id Integer] [:problems/title String]
         [:problems/description String] [:solutions/is_solved
                                         Boolean]])))))

(defactivity org.bytopia.foreclojure.ProblemGridActivity
  :key :main
  :on-create
  (fn [this bundle]
    (neko.activity/request-window-features! this :indeterminate-progress)
    (.addFlags (.getWindow this) android.view.WindowManager$LayoutParams/FLAG_KEEP_SCREEN_ON)
    (safe-for-ui
     (on-ui
       (let [this (*a)]
         (set-content-view! (*a)
           [:grid-view {:id ::problems-gv
                        :column-width (traits/to-dimension (*a) [160 :dp])
                        :num-columns :auto-fit
                        :stretch-mode :stretch-spacing-uniform
                        :adapter (make-problem-adapter this)
                        :on-item-click (fn [parent _ position __]
                                         (let [id (-> (.getAdapter parent)
                                                      (.getItem position)
                                                      :problems/_id)]
                                           (launch-problem-activity this id)))}])
         (refresh-ui this)
         (reload-from-server this))))
    )

  :on-start refresh-ui

  :on-create-options-menu
  (fn [this menu]
    (safe-for-ui
     (let [online? (api/logged-in?)]
       (menu/make-menu
        menu [[:item {:title "Reload"
                      :icon #res/drawable :android/ic-menu-rotate
                      :show-as-action :always
                      :on-click (fn [_] (safe-for-ui (reload-from-server this)))}]
              [:item {:title (format "testclient (%s)"
                                     (if online? "online" "offline"))
                      :icon (if online?
                              #res/drawable :android/ic-menu-share
                              #res/drawable :android/ic-menu-help)
                      :show-as-action [:always :with-text]}]])))))
