(ns org.bytopia.foreclojure.main
  (:require clojure.set
            [neko.activity :refer [defactivity set-content-view!]]
            [neko.data :refer [like-map]]
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
             [utils :refer [long-running-job]]
             [user :as user]])
  (:import android.content.Intent
           android.text.Html
           android.app.Activity
           android.view.View
           [android.widget CursorAdapter GridView TextView]
           android.app.AlertDialog android.app.AlertDialog$Builder
           android.app.Dialog
           android.content.DialogInterface$OnClickListener))

(defelement :grid-view
  :classname GridView
  :traits [:on-item-click])

(defn hide-solved-problem? [a]
  (-> (neko.data/get-shared-preferences a "4clojure" :private)
      like-map
      :hide-solved?))

(defn refresh-ui [a]
  (let [user (:user (like-map (.getIntent a)))]
    (doto (.getAdapter (find-view a ::problems-gv))
      (.changeCursor (db/get-problems-cursor
                      a user (not (hide-solved-problem? a))))
      (.notifyDataSetChanged))
    (.invalidateOptionsMenu a)))

;; (on-ui (refresh-ui (*a)))

(defn reload-from-server [a]
  (long-running-job
   (when (api/network-connected?)
     (let [user (:user (like-map (.getIntent a)))]
       (when (not (api/logged-in?))
         (user/login-via-saved a user true))
       (let [last-id (db/initialize a)
             {:keys [solved-ids new-ids]} (api/fetch-solved-problem-ids last-id)
             locally-solved (db/get-solved-ids-for-user a user)
             to-download (clojure.set/difference solved-ids locally-solved)
             to-upload (clojure.set/difference locally-solved solved-ids)]
         ;; Mark new problems as solved although we don't have the code yet.
         (doseq [problem-id to-download]
           (neko.log/d "Marking problem " problem-id " as solved.")
           (db/update-solution a user problem-id {:is_solved true, :code nil}))
         ;; Upload solutions to locally solved problems which aren't synced.
         (doseq [problem-id to-upload
                 :let [solution (db/get-solution a user problem-id)]]
           (neko.log/d "Sumbitting solution " problem-id solution)
           (if (api/submit-solution problem-id (:solutions/code solution))
             (db/update-solution a user problem-id {:is_synced true
                                                    :is_solved true})
             (on-ui (toast a (str "(???) Server rejected our solution to problem " problem-id)))))
         ;; Download new problems and insert them into database
         (doseq [problem-id new-ids]
           (when-let [json (assoc (api/fetch-problem problem-id)
                             "id" problem-id)]
             (db/insert-problem a json)))
         (when (pos? (+ (count new-ids) (count to-download) (count to-upload)))
           (on-ui (toast a (format "Downloaded %d new problem(s).\nDiscovered %d server solution(s).\nUploaded %d local solution(s)."
                                   (count new-ids) (count to-download) (count to-upload)))))))
     (on-ui (refresh-ui a)))))

;; (reload-from-server (*a))

(defn launch-problem-activity
  [a user problem-id]
  (let [intent (Intent. a (resolve 'org.bytopia.foreclojure.ProblemActivity))]
    (.putExtra intent "problem-id" problem-id)
    (.putExtra intent "user" user)
    (.startActivity a intent)))

(defn switch-user [a]
  (user/clear-last-user a)
  (.startActivity
   a (Intent. a (resolve 'org.bytopia.foreclojure.LoginActivity)))
  (.finish a))

(defn toggle-hide-solved [a]
  (let [sp (neko.data/get-shared-preferences a "4clojure" :private)
        previous (-> sp like-map :hide-solved?)]
    (neko.log/d "Toggled" previous)
    (-> sp .edit (neko.data/assoc! :hide-solved? (not previous)) .commit)
    (refresh-ui a)))

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
    (neko.log/d "onCreate()" this)
    (neko.activity/request-window-features! this :indeterminate-progress)
    (.addFlags (.getWindow this) android.view.WindowManager$LayoutParams/FLAG_KEEP_SCREEN_ON)
    (safe-for-ui
     (on-ui
       (let [this (*a)
             user (-> (neko.data/get-shared-preferences this "4clojure" :private)
                      neko.data/like-map
                      :last-user)]
         (.putExtra (.getIntent this) "user" user)
         (set-content-view! this
           [:grid-view {:id ::problems-gv
                        :column-width (traits/to-dimension this [160 :dp])
                        :num-columns :auto-fit
                        :stretch-mode :stretch-spacing-uniform
                        :adapter (make-problem-adapter this)
                        :on-item-click (fn [parent _ position __]
                                         (let [id (-> (.getAdapter parent)
                                                      (.getItem position)
                                                      :problems/_id)]
                                           (launch-problem-activity this user id)))}])
         (refresh-ui this)
         (reload-from-server this))))
    )

  :on-start refresh-ui

  :on-create-options-menu
  (fn [this menu]
    (safe-for-ui
     (let [user (:user (like-map (.getIntent this)))
           online? (api/logged-in?)]
       (menu/make-menu
        menu [[:item {:title "Show solved"
                      :show-as-action :never
                      :checkable true
                      :checked (not (hide-solved-problem? this))
                      :on-click (fn [_] (safe-for-ui (toggle-hide-solved this)))}]
              [:item {:title "Reload"
                      :icon #res/drawable :org.bytopia.foreclojure/ic-menu-refresh
                      :show-as-action :always
                      :on-click (fn [_] (safe-for-ui (reload-from-server this)))}]
              [:item {:title (format "%s (%s)" user
                                     (if online? "online" "offline"))
                      :icon #res/drawable :org.bytopia.foreclojure/ic-menu-friendslist
                      :show-as-action [:always :with-text]
                      :on-click (fn [_] (safe-for-ui (.showDialog this 0)))}]])))))

;; (on-ui (refresh-ui (*a)))

(defn ProblemGridActivity-onCreateDialog [this id _]
  (safe-for-ui
   (when (= id 0)
     (-> (AlertDialog$Builder. this)
         (.setMessage (str "Do you want to log out of the current account? "
                           "Pressing OK will return you to login form."))
         (.setCancelable true)
         (.setPositiveButton "OK" (reify DialogInterface$OnClickListener
                                    (onClick [_ dialog id]
                                      (switch-user this))))
         (.setNegativeButton "Cancel" (reify DialogInterface$OnClickListener
                                        (onClick [_ dialog id]
                                          (.cancel dialog))))
         .create))))

;; (on-ui (.showDialog (*a) 0))
