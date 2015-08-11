(ns org.bytopia.foreclojure.main
  (:require clojure.set
            [clojure.java.io :as io]
            [neko.activity :refer [defactivity set-content-view! get-state]]
            [neko.data :refer [like-map]]
            [neko.debug :refer [*a]]
            [neko.find-view :refer [find-view find-views]]
            [neko.log :as log]
            [neko.notify :refer [toast]]
            [neko.intent :as intent]
            [neko.dialog.alert :refer [alert-dialog-builder]]
            neko.resource
            [neko.threading :refer [on-ui]]
            [neko.ui :as ui]
            [neko.ui.adapters :as adapters]
            [neko.ui.mapping :refer [defelement]]
            [neko.ui.menu :as menu]
            [neko.ui.traits :as traits]
            [org.bytopia.foreclojure
             [db :as db]
             [api :as api]
             [utils :as utils :refer [long-running-job on-refresh-listener-call]]
             [user :as user]])
  (:import android.app.Activity
           android.graphics.Color
           android.graphics.drawable.Drawable
           android.text.Html
           android.util.LruCache
           android.view.View
           java.util.HashMap
           [android.widget CursorAdapter GridView TextView ProgressBar]
           java.util.concurrent.LinkedBlockingDeque
           android.support.v4.view.ViewCompat
           [android.support.v4.widget DrawerLayout DrawerLayout$DrawerListener]
           [android.support.v7.app AppCompatActivity ActionBarDrawerToggle]))

(neko.resource/import-all)

(defelement :grid-view
  :classname GridView
  :traits [:on-item-click])

(defn hide-solved-problem? []
  (:hide-solved? @user/prefs))

(defn refresh-ui [^Activity a]
  (adapters/update-cursor (.getAdapter ^GridView (find-view a ::problems-gv)))
  (.syncState (find-view (*a) :neko.ui/drawer-toggle))
  (.invalidateOptionsMenu a))

;; (on-ui (refresh-ui (*a)))

(defn reload-from-server [a]
  (long-running-job
   nil ; progress-start
   (.setRefreshing (find-view a ::refresh-lay) false) ; progress-stop

   (if (api/network-connected?)
     (let [user (:user (like-map (.getIntent a)))]
       ;; Try relogin
       (when (not (api/logged-in?))
         (user/login-via-saved user true))
       (if (api/logged-in?)
         (let [last-id (db/initialize a)
               {:keys [solved-ids new-ids]} (api/fetch-solved-problem-ids last-id)
               locally-solved (db/get-solved-ids-for-user user)
               to-download (clojure.set/difference solved-ids locally-solved)
               to-upload (clojure.set/difference locally-solved solved-ids)]
           ;; Mark new problems as solved although we don't have the code yet.
           (doseq [problem-id to-download]
             (neko.log/d "Marking problem " problem-id " as solved.")
             (db/update-solution user problem-id {:is_solved true, :code nil}))
           ;; Upload solutions to locally solved problems which aren't synced.
           (doseq [problem-id to-upload
                   :let [solution (db/get-solution user problem-id)]]
             (neko.log/d "Sumbitting solution " problem-id solution)
             (if (api/submit-solution problem-id (:solutions/code solution))
               (db/update-solution user problem-id {:is_synced true
                                                    :is_solved true})
               (on-ui (toast (str "(???) Server rejected our solution to problem " problem-id)))))
           ;; Download new problems and insert them into database
           (doseq [problem-id new-ids]
             (when-let [json (api/fetch-problem problem-id)]
               (db/insert-problem (assoc json "id" problem-id))))
           (when (pos? (+ (count new-ids) (count to-download) (count to-upload)))
             (on-ui (toast (format "Downloaded %d new problem(s).\nDiscovered %d server solution(s).\nUploaded %d local solution(s)."
                                   (count new-ids) (count to-download) (count to-upload))))))
         (on-ui (toast "Can't login to 4clojure.com. Working in offline mode."))))
     (on-ui (toast "Network is not available.")))
   (on-ui (refresh-ui a))))

;; (reload-from-server (*a))

(defn load-userpic [a username]
  (future
    (log/d "Initializing userpic for" username)
    (let [img (io/file (.getFilesDir (*a)) (str username ".jpg"))]
      (when-not (.exists img)
        (api/download-user-pic a username))
      (when (.exists img)
        (on-ui (ui/config (find-view (*a) ::navbar-userpic)
                          :image (Drawable/createFromPath (str img))))))))

;; (load-userpic (*a) "unlogic")

(defn launch-problem-activity
  [a user problem-id]
  (.startActivity a (intent/intent a '.ProblemActivity
                                   {:problem-id problem-id, :user user})))

(defn switch-user [a]
  (user/clear-last-user a)
  (.startActivity a (intent/intent a '.LoginActivity {}))
  (.finish a))

(defn toggle-hide-solved [a]
  (swap! user/prefs #(assoc % :hide-solved? (not (:hide-solved? %))))
  (refresh-ui a))

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
  []
  (let [dead-switch (atom false)]
    (-> (Thread. (fn []
                   (log/d "Preloading thread started")
                   (while (not @dead-switch)
                     (let [id (.take problem-queue)]
                       (neko.log/d "Preloading from id " id)
                       ;; Cache next 10 elements after requested one
                       (doall (map (fn [i]
                                     (try (problem-description-str (db/get-problem i))
                                          (catch Exception _ nil)))
                                   (range id (+ id 10))))))
                   (log/d "Preloading thread stopped")))
        .start)
    (fn [] (reset! dead-switch true))))

;; (start-preloading-thread)

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
                    :layout-align-parent-bottom true
                    :layout-align-parent-right true
                    :layout-margin-bottom [5 :dp]
                    :layout-margin-right [5 :dp]}]])
   (fn [view _ data]
     (let [[^TextView title, ^TextView desc, ^View done]
           (find-views view ::title-tv ::desc-tv ::done-iv)

           id (:problems/_id data)]
       (.setText title ^String (str id ". " (:problems/title data)))
       (.setText desc ^String (problem-description-str data))
       (when (= (mod id 10) 1)
         (.put problem-queue id))
       (.setVisibility done (if (:solutions/is_solved data)
                              View/VISIBLE View/GONE))))
   (fn [] (db/get-problems-cursor user (not (hide-solved-problem?))))))

(defn make-navbar-header-layout [username]
  (concat
   [:relative-layout {:layout-width :fill
                      :layout-height [215 :dp]}
    [:image-view {:id ::navbar-userpic
                  :layout-width [76 :dp]
                  :layout-height [76 :dp]
                  :layout-margin-top [10 :dp]
                  :layout-center-horizontal true}]
    [:text-view {:id ::navbar-username
                 :text username
                 :layout-width :wrap
                 :layout-margin-top [5 :dp]
                 :layout-margin-bottom [10 :dp]
                 :layout-center-horizontal true
                 :layout-below ::navbar-userpic}]]

   (let [dif-pairs (->> (db/get-solved-count-by-difficulty username)
                        (cons nil)
                        (partition 2 1))
         pb-style android.R$attr/progressBarStyleHorizontal]
     (->> (map (fn [[[prev-dif _] [dif [solved all]]]]
                 (let [ns "org.bytopia.foreclojure.main"
                       prev-tv-id (if prev-dif
                                    (keyword ns (str "navbar-diftv-" prev-dif))
                                    ::navbar-username)
                       curr-tv-id (keyword ns (str "navbar-diftv-" dif))]
                   [[:text-view {:id curr-tv-id
                                 :layout-below prev-tv-id
                                 :layout-margin-left [10 :dp]
                                 :text dif}]
                    [:progress-bar {:indeterminate false
                                    :custom-constructor
                                    (fn [c] (ProgressBar. c nil pb-style))
                                    :layout-width [100 :dp]
                                    :layout-margin-right [10 :dp]
                                    :layout-margin-top [3 :dp]
                                    :layout-below prev-tv-id
                                    :layout-align-parent-right true
                                    :progress (int (* (/ solved all) 100))}]]))
               dif-pairs)
          (apply concat)))))

(alter-var-root #'neko.find-view/nil-or-view?
                (fn [v]
                  (fn [x] true)))

(defactivity org.bytopia.foreclojure.ProblemGridActivity
  :key :main
  :extends AppCompatActivity

  (onCreate [this bundle]
    (.superOnCreate this bundle)
    (neko.debug/keep-screen-on this)
    (.addFlags (.getWindow (*a))
               android.view.WindowManager$LayoutParams/FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    (on-ui
      (let [;; this (*a)
            user (:last-user @user/prefs)]
        (.setDisplayHomeAsUpEnabled (.getSupportActionBar (*a)) true)
        (.setHomeButtonEnabled (.getSupportActionBar (*a)) true)
        (intent/put-extras (.getIntent this) {:user user})
        (set-content-view! this
          [:drawer-layout {:id ::drawer
                           :drawer-indicator-enabled true}
           [:swipe-refresh-layout {:id ::refresh-lay
                                   :color-scheme-resources (into-array Integer/TYPE
                                                                       [R$color/blue
                                                                        R$color/dim_blue])
                                   :on-refresh (fn [_] (reload-from-server this))}
            [:grid-view {:id ::problems-gv
                         :column-width (traits/to-dimension this [160 :dp])
                         :num-columns :auto-fit
                         :stretch-mode :stretch-column-width
                         :background-color (Color/rgb 229 229 229)
                         :horizontal-spacing (traits/to-dimension this [8 :dp])
                         :vertical-spacing (traits/to-dimension this [8 :dp])
                         :padding [8 :dp]
                         :clip-to-padding false
                         :selector R$drawable/card_ripple
                         :draw-selector-on-top true
                         :adapter (make-problem-adapter this user)
                         :on-item-click (fn [^GridView parent, _ position __]
                                          (let [id (-> (.getAdapter parent)
                                                       (.getItem position)
                                                       :problems/_id)]
                                            (launch-problem-activity this user id)))}]]
           [:navigation-view {:layout-width [200 :dp]
                              :layout-height :fill
                              :layout-gravity :left
                              :header (make-navbar-header-layout user)
                              :menu [[:item {:title "Log out"
                                             :icon R$drawable/ic_exit_to_app_black
                                             :show-as-action [:always :with-text]
                                             :on-click (fn [_] (.showDialog this 0))}]]}]])
        (refresh-ui this)
        (future (require 'org.bytopia.foreclojure.problem))
        (reload-from-server this)
        (load-userpic this user)))
    )

  (onStart [this]
    (.superOnStart this)
    (refresh-ui this)
    (swap! (get-state this) assoc
           :pr-thread (start-preloading-thread)))

  (onStop [this]
    (.superOnStop this)
    ((:pr-thread @(get-state this))))

  (onCreateOptionsMenu [this menu]
    (.superOnCreateOptionsMenu this menu)
    (let [user (:user (like-map (.getIntent this)))
          online? (api/logged-in?)]
      (menu/make-menu
       menu [[:item {:title "Show solved"
                     :show-as-action :never
                     :checkable true
                     :checked (not (hide-solved-problem?))
                     :on-click (fn [_] (toggle-hide-solved this))}]
             [:item {:title "Reload"
                     :icon R$drawable/ic_refresh_white
                     :show-as-action :always
                     :on-click (fn [_]
                                 (.setRefreshing (find-view this ::refresh-lay) true)
                                 (reload-from-server this))}]]))
    true)

  (onOptionsItemSelected [this item]
    false
    (if (.onOptionsItemSelected (find-view this :neko.ui/drawer-toggle) item)
      true
      (.superOnOptionsItemSelected this item)))

  (onPostCreate [this bundle]
    (.superOnPostCreate this bundle)
    (.syncState (find-view (*a) :neko.ui/drawer-toggle)))

  (onConfigurationChanged [this new-config]
    (.superOnConfigurationChanged this new-config)
    (.onConfigurationChanged (find-view this :neko.ui/drawer-toggle) new-config))

  (onCreateDialog [this id _]
    (when (= id 0)
      (-> this
          (alert-dialog-builder
           {:message (str "Do you want to log out of the current account? "
                          "Pressing OK will return you to login form.")
            :cancelable true
            :positive-text "OK"
            :positive-callback (fn [_ __] (switch-user this))
            :negative-text "Cancel"
            :negative-callback (fn [dialog _] (.cancel dialog))})
          .create))))

;; (on-ui (refresh-ui (*a)))

;; (on-ui (.showDialog (*a) 0))
