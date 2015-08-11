(ns org.bytopia.foreclojure.problem
  (:require [clojure.string :as str]
            [neko.action-bar :as action-bar]
            [neko.activity :refer [defactivity set-content-view! get-state]]
            [neko.context :refer [get-service]]
            [neko.data :refer [like-map]]
            [neko.debug :refer [*a]]
            [neko.find-view :refer [find-view find-views]]
            [neko.intent :refer [intent]]
            [neko.log :as log]
            [neko.notify :refer [toast]]
            neko.resource
            [neko.threading :refer [on-ui]]
            [neko.ui :as ui]
            [neko.ui.menu :as menu]
            [neko.ui.traits :as traits]
            [org.bytopia.foreclojure
             [api :as api]
             [db :as db]
             [logic :as logic]
             [utils :refer [long-running-job snackbar ellipsize]]])
  (:import android.content.res.Configuration
           android.graphics.Typeface
           android.graphics.Color
           [android.text Html InputType Spannable]
           android.view.View
           android.view.inputmethod.EditorInfo
           [android.widget EditText ListView TextView]
           [org.bytopia.foreclojure SafeLinkMethod CodeboxTextWatcher]
           android.support.v7.app.AppCompatActivity
           neko.App))

(neko.resource/import-all)

(defn- get-string-array [res-id]
  (.getStringArray (.getResources App/instance) res-id))

(defn congratulations-message []
  (format "%s %s! Proceed to the next problem."
          (rand-nth (get-string-array R$array/nice_adjectives))
          (rand-nth (get-string-array R$array/work_synonyms))))

(defmacro lrj [& body]
  `(long-running-job
    (ui/config (find-view ~'a ::eval-progress) :visibility :visible)
    (ui/config (find-view ~'a ::eval-progress) :visibility :gone)
    ~@body))

;;; Interaction

(defn repl-mode? [a]
  (:repl-mode @(get-state a)))

(defn toggle-repl-mode [a]
  (let [repl? (:repl-mode (swap! (get-state a) update :repl-mode not))]
    (ui/config (find-view a ::repl-out) :visibility (if repl? :visible :gone))
    (.invalidateOptionsMenu a)))

;; (on-ui (toggle-repl-mode (*a)))

(defn update-test-status
  "Takes a list of pairs, one pair per test, where first value is
  either :good, :bad or :none; and second value is error message."
  [a status-list]
  (let [tests-lv (find-view a ::tests-lv)]
    (mapv (fn [test-i [imgv-state err-msg]]
            (let [test-view (find-view tests-lv test-i)
                  [imgv errv] (find-views test-view ::status-iv ::error-tv)]
              (ui/config imgv
                         :visibility (if (= imgv-state :none)
                                       :invisible :visible)
                         :image (if (= imgv-state :good)
                                  R$drawable/ic_checkmark
                                  R$drawable/ic_cross))
              (ui/config errv :text (str err-msg))))
          (range (count (:tests (:problem @(get-state a)))))
          status-list)))

(defn try-solution [a code]
  (let [{:keys [tests restricted]} (:problem @(get-state a))
        results (logic/check-suggested-solution code tests restricted)]
    (on-ui (->> (range (count tests))
                (mapv (fn [i] (let [err-msg (get results i)]
                               [(if err-msg :bad :good) err-msg])))
                (update-test-status a)))
    (empty? results)))

;; (on-ui (try-solution (*a) ""))

(defn save-solution
  [a code correct?]
  (let [{:keys [problem-id user]} (like-map (.getIntent a))]
    (db/update-solution user problem-id {:code code, :is_solved correct?})))

(defn run-solution
  "Runs the expression in code buffer against problem tests."
  [a]
  (let [{:keys [problem-id user]} (like-map (.getIntent a))
        code (str (.getText ^EditText (find-view a ::codebox)))]
    (lrj
     (let [correct? (try-solution a code)]
       (save-solution a code correct?)
       (when correct?
         (when (and (api/network-connected?) (api/logged-in?))
           (let [success? (api/submit-solution problem-id code)]
             (if success?
               (do
                 (db/update-solution user problem-id {:is_solved true
                                                      :is_synced true})
                 (when-let [focused (.getCurrentFocus a)]
                   (.hideSoftInputFromWindow (get-service :input-method)
                                             (.getWindowToken focused) 0)
                   (Thread/sleep 100))
                 (let [next-id (db/get-next-unsolved-id user problem-id)]
                   (snackbar a (congratulations-message)
                             20000 (when next-id "Next problem")
                             (fn [v]
                               (.startActivity
                                a (intent a '.ProblemActivity
                                          {:problem-id next-id
                                           :user user}))
                               (.finish a)
                               (.overridePendingTransition a R$anim/slide_in_right R$anim/slide_out_left)))))
               (on-ui (toast "Server rejected our solution!\nPlease submit a bug report."))))))))))

;; (run-solution (*a))

(defn run-repl
  "Runs the expression in code buffer as-is and show the results."
  [a]
  (let [code (str (.getText ^EditText (find-view a ::codebox)))
        ^TextView repl-out (find-view a ::repl-out)]
    (lrj
     (let [result (logic/run-code-in-repl code)
           str-to-append (str "\n"
                              (:out result)
                              (when (contains? result :result)
                                (str "=> " (ellipsize (str (or (:result result) "nil")) 200))))]
       (on-ui (.append repl-out str-to-append))
       (save-solution a code false)))))

;; (run-repl (*a))

(defn refresh-ui [a code solved?]
  (let [[codebox solved-iv repl-out]
        (find-views a ::codebox ::solved-iv ::repl-out)]
    (ui/config codebox :text code)
    (ui/config solved-iv :visibility (if solved? :visible :invisible))
    (ui/config repl-out :visibility (if (repl-mode? a) :visible :gone))))

;; (on-ui (refresh-ui (*a) "foobar" true))

(defn check-solution-on-server [a solution]
  (lrj
   (when (and (api/network-connected?) (api/logged-in?))
     (let [{:keys [problem-id user]} (like-map (.getIntent a))]
       (when (and (:solutions/is_solved solution)
                  (nil? (:solutions/code solution)))
         ;; Apparently there should be our solution on server, let's grab it.
         (when-let [code (api/fetch-user-solution problem-id)]
           (db/update-solution user problem-id {:code code, :is_solved true
                                                :is_synced true})
           (on-ui (refresh-ui a code true))))))))

;; (on-ui (check-solution-on-server (*a) nil))

(defn clear-result-flags [a]
  (update-test-status a (repeat [:none ""])))

(def core-forms
  (conj (->> (find-ns 'clojure.core)
             ns-map keys
             (keep #(re-matches #"^[a-z].*" (str %)))
             set)
        "if" "do" "recur"))

;; (on-ui (clear-result-flags (*a)))

;;; UI views

(defn make-test-row [i test]
  [:linear-layout {:id-holder true, :id i
                   :layout-margin-top [5 :dp]
                   :layout-margin-bottom [5 :dp]}
   [:image-view {:id ::status-iv
                 :image R$drawable/ic_checkmark
                 :scale-type :fit-xy
                 :layout-width [18 :dp]
                 :layout-height [18 :dp]
                 :visibility :invisible
                 :layout-gravity :center}]
   [:text-view {:text (.replace (str test) "\\r\\n" "\n")
                :layout-margin-left [10 :dp]
                :layout-height :wrap
                :layout-width 0
                :layout-weight 1
                :typeface android.graphics.Typeface/MONOSPACE
                :layout-gravity :center}]
   [:text-view {:id ::error-tv
                :text-color (Color/rgb 200 0 0)
                :layout-width 0
                :layout-weight 1
                :layout-margin-left [15 :dp]
                :layout-gravity :center
                :on-click (fn [^View v] (clear-result-flags (.getContext v)))}]])

(defn make-tests-list [tests under]
  (list* :linear-layout {:id ::tests-lv
                         :layout-below under
                         :id-holder true
                         :orientation :vertical
                         :layout-margin-top [10 :dp]
                         :layout-margin-left [10 :dp]}
         (interpose [:view {:layout-width :fill
                            :background-color (Color/argb 31 0 0 0)
                            :layout-height [1 :dp]}]
                    (map-indexed make-test-row tests))))

(defn- render-html
  "Sometimes description comes to us in HTML. Let's make it pretty."
  [^String html]
  (let [^Spannable spannable
        (-> html
            (.replace "</li>" "</li><br>")
            (.replace "<li>" "<li>&nbsp; • &nbsp;")
            ;; Fix internal links
            (.replace "<a href=\"/" "<a href=\"http://4clojure.com/")
            (.replace "<a href='/" "<a href='http://4clojure.com/")
            Html/fromHtml)]
    ;; Remove extra newlines introduced by <p> tag.
    (loop [i (dec (.length spannable))]
      (if (= (.charAt spannable i) \newline)
        (recur (dec i))
        (.delete spannable (inc i) (.length spannable))))))

(defn problem-ui [a {:keys [_id title difficulty description restricted tests]}]
  [:scroll-view {}
   [:linear-layout {:id ::top-container
                    :orientation :vertical
                    :layout-transition (android.animation.LayoutTransition.)}
    [:relative-layout {:focusable true
                       :id ::container
                       :focusable-in-touch-mode true
                       :background-color Color/WHITE
                       :layout-margin [5 :dp]
                       :layout-width :fill
                       :elevation [1 :dp]
                       :padding [5 :dp]}
     [:text-view {:id ::title-tv
                  :text (str _id ". " title)
                  :text-size [24 :sp]
                  :text-color (Color/rgb 33 33 33)
                  :typeface Typeface/DEFAULT_BOLD}]
     (when (= (.. a (getResources) (getConfiguration) orientation)
              Configuration/ORIENTATION_LANDSCAPE)
       [:text-view {:text difficulty
                    :layout-align-parent-top true
                    :layout-align-parent-right true
                    :text-size [18 :sp]
                    :text-color (Color/rgb 33 33 33)
                    :padding [5 :dp]}])
     [:image-view {:id ::solved-iv
                   :layout-to-right-of ::title-tv
                   :image R$drawable/ic_checkmark_large
                   :layout-height [24 :sp]
                   :layout-width [24 :sp]
                   :layout-margin-left [10 :dp]
                   :layout-margin-top [5 :dp]}]
     [:text-view {:id ::desc-tv
                  :layout-below ::title-tv
                  :text (render-html description)
                  :text-color (Color/rgb 33 33 33)
                  :movement-method (SafeLinkMethod/getInstance)
                  :link-text-color (android.graphics.Color/rgb 0 0 139)}]
     (when (seq restricted)
       [:text-view {:id ::restricted-tv
                    :layout-below ::desc-tv
                    :text-color (Color/rgb 33 33 33)
                    :text (->> restricted
                               (str/join ", ")
                               (str "Special restrictions: "))}])
     (make-tests-list tests (if (seq restricted)
                              ::restricted-tv  ::desc-tv))]
    [:text-view {:id ::repl-out
                 :layout-margin [5 :dp]
                 :padding [3 :dp]
                 :layout-width :fill
                 :visibility :gone
                 :gravity :bottom
                 :max-lines 6
                 :movement-method (android.text.method.ScrollingMovementMethod.)
                 :min-height (neko.ui.traits/to-dimension a [110 :sp])
                 :typeface Typeface/MONOSPACE
                 :text-color (Color/rgb 33 33 33)
                 :background-color Color/WHITE
                 :text ";; In REPL mode code is evaluated as-is. Press \"Run\" to see the result of your expression. *out* is redirected to here too. This view is scrollable."}]
    [:relative-layout {}
     [:progress-bar {:id ::eval-progress
                     :layout-align-parent-right true
                     :layout-margin-right [10 :dp]
                     :visibility :gone}]
     [:edit-text {:id ::codebox
                  :input-type (bit-or InputType/TYPE_TEXT_FLAG_NO_SUGGESTIONS
                                      InputType/TYPE_TEXT_FLAG_MULTI_LINE)
                  :ime-options EditorInfo/IME_FLAG_NO_EXTRACT_UI
                  :single-line false
                  :layout-margin-top [15 :dp]
                  :layout-width :fill
                  :typeface Typeface/MONOSPACE
                  :hint "Type code here"}]]]])

(defactivity org.bytopia.foreclojure.ProblemActivity
  :key :problem
  :extends AppCompatActivity

  (onCreate [this bundle]
    (.superOnCreate this bundle)
    (neko.debug/keep-screen-on this)
    (let [;; this (*a)
          ]
      (on-ui
        (let [{:keys [problem-id user]} (like-map (.getIntent this))
              problem (db/get-problem problem-id)
              solution (db/get-solution user problem-id)
              code (or (:solutions/code solution) "")
              solved? (and solution (:solutions/is_solved solution))]
          (swap! (get-state this) assoc :problem problem, :solution solution)
          (set-content-view! this (problem-ui this problem))
          (.addTextChangedListener ^EditText (find-view this ::codebox)
                                   (CodeboxTextWatcher. core-forms))
          (refresh-ui this code solved?)
          (.setDisplayHomeAsUpEnabled (.getSupportActionBar this) true)
          (.setHomeButtonEnabled (.getSupportActionBar this) true)
          (.setTitle (.getSupportActionBar this) (str "Problem " (:_id problem)))
          (check-solution-on-server this solution))))
    )

  (onStop [this]
    (.superOnStop this)
    (let [code (str (.getText ^EditText (find-view this ::codebox)))]
      (when-not (= code "")
        (save-solution this code false))))

  (onCreateOptionsMenu [this menu]
    (.superOnCreateOptionsMenu this menu)
    (let [repl-mode (repl-mode? this)]
      (menu/make-menu
       menu [[:item {:title (if repl-mode
                              "Switch to problem mode"
                              "Switch to REPL mode")
                     :icon (if repl-mode
                             R$drawable/ic_format_list_bulleted_white
                             R$drawable/ic_mode_edit_white)
                     :show-as-action :always
                     :on-click (fn [_] (toggle-repl-mode this))}]
             [:item {:title "Run"
                     :icon R$drawable/ic_directions_run_white
                     :show-as-action [:always :with-text]
                     :on-click (fn [_] (if (repl-mode? this)
                                        (run-repl this)
                                        (run-solution this)))}]]))
    true)

  (onOptionsItemSelected [this item]
    (if (= (.getItemId item) android.R$id/home)
      (.finish this)
      (.superOnOptionsItemSelected this item))
    true)

  (onSaveInstanceState [this bundle]
    (.putBoolean bundle "repl-mode" (boolean (repl-mode? this)))
    (.putString bundle "repl-out" (str (.getText (find-view this ::repl-out)))))

  (onRestoreInstanceState [this bundle]
    (let [b (like-map bundle)]
      (ui/config (find-view this ::repl-out) :text (:repl-out b))
      (when (:repl-mode b)
        (toggle-repl-mode this)))))
