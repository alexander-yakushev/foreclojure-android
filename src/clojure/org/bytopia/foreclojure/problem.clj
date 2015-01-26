(ns org.bytopia.foreclojure.problem
  (:require clojure.walk
            [neko.action-bar :as bar]
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
             [api :as api]
             [db :as db]
             [logic :as logic]])
  (:import android.text.Html
           android.text.SpannableStringBuilder
           [android.widget EditText GridView ListView TextView]))

;;; Interaction

(defn update-test-status
  "Takes a list of pairs, one pair per test, where first value is
  either :good, :bad or :none; and second value is error message."
  [a status-list]
  (let [^ListView tests-lv (find-view a ::tests-lv)]
    (mapv (fn [test-i [imgv-state err-msg]]
            (let [test-view (find-view tests-lv test-i)
                  [imgv errv] (find-views test-view ::status-iv ::error-tv)]
              (ui/config imgv
                         :visibility (if (= imgv-state :none)
                                       android.view.View/INVISIBLE
                                       android.view.View/VISIBLE)
                         :image (if (= imgv-state :good)
                                  #res/drawable :org.bytopia.foreclojure/check_icon
                                  #res/drawable :android/ic-delete))
              (ui/config errv :text (str err-msg))))
          (range (.getChildCount tests-lv))
          status-list)))

(defn try-solution [a code]
  (let [state @(.state a)
        results (logic/check-suggested-solution code (:tests state)
                                                (:restricted state))]
    (on-ui (->> (range (count (:tests state)))
                (mapv (fn [i] (let [err-msg (get results i)]
                               [(if err-msg :bad :good) err-msg])))
                (update-test-status a)))
    (empty? results)))

;; (on-ui (try-solution (*a) ""))

(defn save-solution
  [a code correct?]
  (let [{:keys [problem-id user]} (like-map (.getIntent a))]
    (db/update-solution a user problem-id {:code code, :is_solved correct?})))

(defn run-solution [a]
  (.setProgressBarIndeterminateVisibility a true)
  (let [{:keys [problem-id user]} (like-map (.getIntent a))
        code (str (.getText ^EditText (find-view a ::codebox)))]
    (future
      (try
        (let [correct? (try-solution a code)]
          (save-solution a code correct?)
          (when correct?
            (when (and (api/network-connected?) (api/logged-in?))
              (let [success? (api/submit-solution problem-id code)]
                (if success?
                  (db/update-solution a user problem-id {:is_solved true
                                                         :is_synced true})
                  (throw (RuntimeException. "Server rejected our solution!
Please submit a bug report.")))))
           (on-ui (toast a "Correct! Please proceed to the next problem."))))
        (catch Exception ex (on-ui (toast a (str ex))))
        (finally (on-ui (.setProgressBarIndeterminateVisibility a false)))))))

;; (on-ui (run-solution (*a)))

(defn check-solution-on-server [a solution]
  (.setProgressBarIndeterminateVisibility a true)
  (future
    (try
      (when (and (api/network-connected?) (api/logged-in?))
        (let [{:keys [problem-id user]} (like-map (.getIntent a))]
          (when (and (:solutions/is_solved solution)
                     (nil? (:solutions/code solution)))
            ;; Apparently there should be our solution on server, let's grab it.
            (when-let [code (api/fetch-user-solution problem-id)]
              (on-ui (toast a code))
              (db/update-solution a user problem-id {:code code, :is_solved true
                                                     :is_synced true})))))
      (catch Exception ex (on-ui (toast a (str ex))))
      (finally (on-ui (.setProgressBarIndeterminateVisibility a false))))))

;; (on-ui (check-solution-on-server (*a) nil))

(defn clear-result-flags [a]
  (update-test-status a (repeat [:none ""])))

;; (on-ui (clear-result-flags (*a)))

;;; UI views

(defn make-test-row
  [i test]
  [:linear-layout {:id-holder true, :id i
                   :layout-margin-top [10 :dp]}
   [:image-view {:id ::status-iv
                 :image #res/drawable :android/ic-delete
                 :scale-type :fit-xy
                 :layout-width (neko.ui.traits/to-dimension (*a) [20 :dp])
                 :layout-height (neko.ui.traits/to-dimension (*a) [20 :dp])
                 :visibility android.view.View/INVISIBLE
                 :layout-gravity android.view.Gravity/CENTER}]
   [:text-view {:text (.replace (str test) "\\r\\n" "\n")
                :layout-margin-left [10 :dp]
                :layout-height :wrap
                :layout-width 0
                :layout-weight 1
                :typeface android.graphics.Typeface/MONOSPACE
                :layout-gravity android.view.Gravity/CENTER}]
   [:text-view {:id ::error-tv
                :text-color (android.graphics.Color/rgb 200 0 0)
                :layout-width 0
                :layout-weight 1
                :layout-margin-left [15 :dp]
                :layout-gravity android.view.Gravity/CENTER
                :on-click (fn [v] (clear-result-flags (.getContext v)))}]])

(defn make-tests-list
  [tests]
  (list* :linear-layout {:id ::tests-lv
                         :layout-below ::desc-tv
                         :id-holder true
                         :orientation :vertical
                         :layout-margin-top [10 :dp]
                         :layout-margin-left [10 :dp]}
         (map-indexed make-test-row tests)))

(defn- render-html
  "Sometimes description comes to us in HTML. Let's make it pretty."
  [^String html]
  (let [spannable (-> html
                      (.replace "</li>" "</li><br>")
                      (.replace "<li>" "<li>&nbsp; • &nbsp;")
                      Html/fromHtml)]
    ;; Remove extra newlines introduced by <p> tag.
    (loop [i (dec (.length spannable))]
      (if (= (.charAt spannable i) \newline)
        (recur (dec i))
        (.delete spannable (inc i) (.length spannable))))))

(defelement :scroll-view
  :classname android.widget.ScrollView)

;;; Interaction

(defactivity org.bytopia.foreclojure.ProblemActivity
  :key :problem
  :state (atom {})
  :on-create
  (fn [this bundle]
    (let [this (*a)]
      (neko.activity/request-window-features! this :indeterminate-progress)
      (.addFlags (.getWindow this) android.view.WindowManager$LayoutParams/FLAG_KEEP_SCREEN_ON)
      (on-ui
        (let [{:keys [problem-id user]} (like-map (.getIntent this))

              {:keys [_id title difficulty description restricted tests]}
              (db/get-problem this problem-id)

              solution (db/get-solution this problem-id user)
              code (or (:solutions/code solution) "")
              solved? (and solution (:solutions/is_solved solution))]
          (swap! (.state this) assoc :tests tests :restricted restricted
                 :solution solution)
          (check-solution-on-server this solution)
          (bar/setup-action-bar
           this
           {:title (str "Problem " _id)
            :display-options [:show-home :show-title :home-as-up]})
          (set-content-view! this
            [:scroll-view {}
             [:relative-layout {:focusable true
                                :focusable-in-touch-mode true
                                :padding [5 :dp]}
              [:text-view {:id ::title-tv
                           :text (str _id ". " title)
                           :text-size [24 :sp]
                           :typeface android.graphics.Typeface/DEFAULT_BOLD}]
              (when (= (.. this (getResources) (getConfiguration) orientation)
                       android.content.res.Configuration/ORIENTATION_LANDSCAPE)
                [:text-view {:text difficulty
                             :layout-align-parent-top true
                             :layout-align-parent-right true
                             :text-size [18 :sp]
                             :padding [5 :dp]}])
              [:image-view {:id ::solved-iv
                            :layout-to-right-of ::title-tv
                            :image #res/drawable :org.bytopia.foreclojure/check-icon
                            :visibility (if solved? android.view.View/VISIBLE
                                            android.view.View/INVISIBLE)
                            :layout-height [24 :sp]
                            :layout-width [24 :sp]
                            :layout-margin-left [10 :dp]
                            :layout-margin-top [5 :dp]}]
              [:text-view {:id ::desc-tv
                           :layout-below ::title-tv
                           :text (render-html description)}]
              (make-tests-list tests)
              [:edit-text {:id ::codebox
                           :layout-below ::tests-lv
                           :ime-options android.view.inputmethod.EditorInfo/IME_FLAG_NO_EXTRACT_UI
                           :layout-margin-top [20 :dp]
                           :layout-width :fill
                           :typeface android.graphics.Typeface/MONOSPACE
                           :hint "Type code here"
                           :text code}]
              ]]))))
    ))

(defn ProblemActivity-onOptionsItemSelected [this item]
  (safe-for-ui
   (condp = (.getItemId item)
     android.R$id/home (do (.finish this)
                           true)
     (.superOnOptionsItemSelected this item)))
  false)

(defn ProblemActivity-onCreateOptionsMenu [this menu]
  (.superOnCreateOptionsMenu this menu)
  (safe-for-ui
   (menu/make-menu
    menu [[:item {:title "Run"
                  :icon #res/drawable :android/ic-menu-send
                  :show-as-action [:always :with-text]
                  :on-click (fn [_] (safe-for-ui (run-solution this)))}]]))
  true)
