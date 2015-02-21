(ns org.bytopia.foreclojure.main
  (:require clojure.set
            [neko.activity :refer [defactivity set-content-view!]]
            [neko.data :refer [like-map]]
            [neko.debug :refer [*a safe-for-ui]]
            [neko.find-view :refer [find-view find-views]]
            [neko.log :as log]
            [neko.notify :refer [toast]]
            neko.resource
            [neko.threading :refer [on-ui]]
            [neko.ui.adapters :as adapters]
            [neko.ui :as ui]
            [neko.ui.mapping :refer [defelement]]
            [neko.ui.menu :as menu]
            [neko.ui.traits :as traits]
            [org.bytopia.foreclojure
             [db :as db]
             [api :as api]
             [utils :refer [long-running-job]]
             [user :as user]])
  (:import android.app.Activity
           android.app.AlertDialog
           android.app.AlertDialog$Builder
           android.app.Dialog
           android.content.DialogInterface$OnClickListener
           android.content.Intent
           android.text.Html
           android.util.LruCache
           android.view.View
           [android.widget CursorAdapter GridView TextView]
           java.util.concurrent.LinkedBlockingDeque))

(neko.resource/import-all)

(defelement :grid-view
  :classname GridView
  :traits [:on-item-click])

(defn hide-solved-problem? [a]
  (-> (neko.data/get-shared-preferences a "4clojure" :private)
      like-map
      :hide-solved?))

(defn refresh-ui [^Activity a]
  (adapters/update-cursor (.getAdapter ^GridView (find-view a ::problems-gv)))
  (.invalidateOptionsMenu a))

;; (on-ui (refresh-ui (*a)))

(defn reload-from-server [a]
  (long-running-job
   (when (api/network-connected?)
     (let [user (:user (like-map (.getIntent a)))]
       ;; Try relogin
       (when (not (api/logged-in?))
         (user/login-via-saved a user true))
       (when (api/logged-in?)
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
                                     (count new-ids) (count to-download) (count to-upload))))))
         (on-ui "Can't login to 4clojure.com. Working in offline mode.")))
     (on-ui (refresh-ui a)))))

;; (reload-from-server (*a))

(defn launch-problem-activity
  [a user problem-id]
  (let [^Intent intent (Intent. a (resolve 'org.bytopia.foreclojure.ProblemActivity))]
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
    (-> sp .edit (neko.data/assoc! :hide-solved? (not previous)) .commit)
    (refresh-ui a)))

(defn html->short-str [html]
  (let [s (str (Html/fromHtml html))
        lng (count s)]
    (if (> lng 140)
      (str (subs s 0 140) "...") s)))

(def ^LruCache cache
  (memoize (fn [] (LruCache. 50))))

(defn problem-description-str [problem]
  (let [cache (cache)
        id (or (:_id problem) (:problems/_id problem))
        cached-desc (.get cache id)]
    (if-not cached-desc
      (let [desc (html->short-str (or (:description problem)
                                      (:problems/description problem)))]
        (.put cache id desc)
        desc)
      cached-desc)))

(def problem-queue (LinkedBlockingDeque.))

(defn start-preloading-thread
  "Spins a thread that monitors id-queue and preloads next problem descriptions
  onto cache. Returns a function that kills the thread."
  [ctx]
  (let [dead-switch (atom false)]
    (-> (Thread. (fn []
                   (log/d "Preloading thread started")
                   (while (not @dead-switch)
                     (let [id (.take problem-queue)]
                       (neko.log/d "Preloading from id " id)
                       ;; Cache next 10 elements after requested one
                       (doall (map (fn [i]
                                     (try (problem-description-str (db/get-problem ctx i))
                                          (catch Exception _ nil)))
                                   (range id (+ id 10))))))
                   (log/d "Preloading thread stopped")))
        .start)
    (fn [] (reset! dead-switch true))))

;; (start-preloading-thread (*a))

(defn make-problem-adapter [a user]
  (adapters/cursor-adapter
   a
   (fn []
     [:relative-layout {:layout-height [160 :dp]
                        :id-holder true
                        :background-color (android.graphics.Color/WHITE)
                        :padding [5 :dp]}
      [:text-view {:id ::title-tv
                   :text-size [20 :sp]
                   :typeface android.graphics.Typeface/DEFAULT_BOLD}]
      [:text-view {:id ::desc-tv
                   :layout-below ::title-tv}]
      [:image-view {:id ::done-iv
                    :image R$drawable/check_icon
                    :layout-width [50 :dp]
                    :layout-height [50 :dp]
                    :visibility :gone
                    :layout-align-parent-bottom true
                    :layout-align-parent-right true
                    :layout-margin-bottom [5 :dp]
                    :layout-margin-right [5 :dp]}]])
   (fn [view _ data]
     (let [[title desc done] (find-views view ::title-tv ::desc-tv ::done-iv)
           id (:problems/_id data)]
       (.setText ^TextView title
                 ^String (str id ". " (:problems/title data)))
       (.setText ^TextView desc ^String (problem-description-str data))
       (when (= (mod id 10) 1)
         (.put problem-queue id))
       (.setVisibility ^android.view.View
                       done (if (:solutions/is_solved data)
                              View/VISIBLE View/GONE))))
   (fn [] (db/get-problems-cursor a user (not (hide-solved-problem? a))))))

(defactivity org.bytopia.foreclojure.ProblemGridActivity
  :key :main
  :features [:indeterminate-progress]
  :on-create
  (fn [this bundle]
    (neko.debug/keep-screen-on this)
    (on-ui
      (let [;; this (*a)
            user (-> (neko.data/get-shared-preferences this "4clojure" :private)
                     neko.data/like-map
                     :last-user)]
        (.putExtra (.getIntent this) "user" user)
        (set-content-view! this
          [:grid-view {:id ::problems-gv
                       :column-width (traits/to-dimension this [160 :dp])
                       :num-columns :auto-fit
                       :stretch-mode :stretch-column-width
                       :background-color (android.graphics.Color/rgb 229 229 229)
                       :horizontal-spacing (traits/to-dimension this [8 :dp])
                       :vertical-spacing (traits/to-dimension this [8 :dp])
                       :padding [8 :dp]
                       :clip-to-padding false
                       :adapter (make-problem-adapter this user)
                       :on-item-click (fn [^GridView parent, _ position __]
                                        (let [id (-> (.getAdapter parent)
                                                     (.getItem position)
                                                     :problems/_id)]
                                          (launch-problem-activity this user id)))}])
        (refresh-ui this)
        (reload-from-server this)))
    )

  :on-start (fn [this]
              (refresh-ui this)
              (swap! (.state this) assoc
                     :pr-thread (start-preloading-thread this)))

  :on-stop (fn [this]
             ((:pr-thread @(.state this))))

  :on-create-options-menu
  (fn [^org.bytopia.foreclojure.ProblemGridActivity this menu]
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
                      :icon R$drawable/ic_menu_refresh
                      :show-as-action :always
                      :on-click (fn [_] (safe-for-ui (reload-from-server this)))}]
              [:item {:title (format "%s (%s)" user
                                     (if online? "online" "offline"))
                      :icon R$drawable/ic_menu_friendslist
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
