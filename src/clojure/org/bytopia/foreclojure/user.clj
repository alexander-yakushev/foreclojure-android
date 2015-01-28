(ns org.bytopia.foreclojure.user
  (:require clojure.set
            [neko.activity :refer [defactivity set-content-view!]]
            neko.data
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
  (:import android.app.ProgressDialog
           android.content.Intent
           android.content.res.Configuration
           android.text.Html
           android.text.InputType
           android.text.method.LinkMovementMethod
           android.view.Gravity
           android.view.View
           android.widget.EditText
           javax.crypto.Cipher
           javax.crypto.SecretKey
           javax.crypto.spec.SecretKeySpec
           org.bytopia.foreclojure.BuildConfig))

(def secret-key (SecretKeySpec. (.getBytes BuildConfig/ENC_KEY)
                                BuildConfig/ENC_ALGORITHM))

(defn- encrypt-pwd [password]
  (let [cipher (doto (Cipher/getInstance BuildConfig/ENC_ALGORITHM)
                 (.init Cipher/ENCRYPT_MODE secret-key))]
    (.doFinal cipher (.getBytes password))))

(defn- decrypt-pwd [password-bytes]
  (let [cipher (doto (Cipher/getInstance BuildConfig/ENC_ALGORITHM)
                 (.init Cipher/DECRYPT_MODE secret-key))]
    (String. (.doFinal cipher password-bytes))))

(defn set-user-db [a username password]
  (db/update-user a username (encrypt-pwd password)))

(defn lookup-user [a username]
  (update-in (db/get-user a username) [:password] decrypt-pwd))

(defn set-last-user [a username]
  (-> (neko.data/get-shared-preferences a "4clojure" :private)
      .edit
      (neko.data/assoc! :last-user username)
      .commit))

(defn clear-last-user [a]
  (-> (neko.data/get-shared-preferences a "4clojure" :private)
      .edit
      (.remove "last-user")
      .commit))

(defn login-via-input [a]
  (let [[user-et pwd-et] (find-views a ::user-et ::pwd-et)
        username (str (.getText ^EditText user-et))
        password (str (.getText ^EditText pwd-et))
        progress (ProgressDialog/show a nil "Signing in..." true)]
    (neko.log/d "login-via-input()" "username" username "password" password)
    (future
      (try
        (if-let [success? (api/login username password true)]
          (do (set-last-user a username)
              (set-user-db a username password)
              (.startActivity
               a (Intent. a (resolve 'org.bytopia.foreclojure.ProblemGridActivity)))
              (.finish a))
          (on-ui a (toast "Could not sign in. Please check the correctness of your credentials.")))
        (finally (on-ui (.dismiss progress)))))))

(defn login-via-saved [a username force?]
  (let [pwd (:password (lookup-user a username))]
    (api/login username pwd force?)))

(defn register [a]
  (let [[username email pwd pwdx2 :as creds]
        (map (fn [^EditText et] (str (.getText ^EditText et)))
             (find-views a ::user-et ::email-et ::pwd-et ::pwdx2-et))

        progress (ProgressDialog/show a nil "Signing up..." true)]
    (neko.log/d "register()" "creds:" creds)
    (future
      (try
        (let [error (apply api/register creds)]
          (if-not error
            (do (set-last-user a username)
                (set-user-db a username pwd)
                (.startActivity
                 a (Intent. a (resolve 'org.bytopia.foreclojure.ProblemGridActivity)))
                (.finish a))
            (on-ui a (toast a error :long))))
        (catch Exception ex (on-ui (toast a (str "Exception raised: " ex))))
        (finally (on-ui (.dismiss progress)))))))

(defn refresh-ui [a]
  (let [signup-active? (:signup-active? @(.state a))
        [signin signup ll] (find-views a ::signin-but ::signup-but
                                       ::email-and-pwdx2)]
    (ui/config signin :text (if signup-active?
                              "Wait, I got it" "Sign in"))
    (ui/config signup :text (if signup-active?
                              "Register" "No account?"))
    (ui/config ll :visibility (if signup-active?
                                View/VISIBLE View/GONE))))

(defn login-form [landscape?]
  (let [basis {:layout-width 0, :layout-weight 1}]
    [:relative-layout (cond-> {:layout-align-parent-bottom true
                               :layout-margin-bottom [60 :dp]}
                              landscape? (assoc :layout-to-right-of ::gus-logo
                                                :layout-margin-bottom [80 :dp]))
     [:linear-layout {:id ::user-and-pwd
                      :layout-width :fill
                      :layout-margin [10 :dp]}
      [:edit-text (assoc basis
                    :id ::user-et
                    :gravity Gravity/CENTER_HORIZONTAL
                    :input-type (bit-or InputType/TYPE_CLASS_TEXT
                                        InputType/TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                    :hint "username")]
      [:edit-text (assoc basis
                    :id ::pwd-et
                    :layout-margin-left [10 :dp]
                    :gravity Gravity/CENTER_HORIZONTAL
                    :input-type (bit-or InputType/TYPE_CLASS_TEXT
                                        InputType/TYPE_TEXT_VARIATION_PASSWORD)
                    :hint "password")]]
     [:linear-layout {:id ::email-and-pwdx2
                      :layout-below ::user-and-pwd
                      ;; :visibility View/GONE
                      :layout-width :fill
                      :layout-margin [10 :dp]}
      [:edit-text (assoc basis
                    :id ::email-et
                    :gravity Gravity/CENTER_HORIZONTAL
                    :input-type (bit-or InputType/TYPE_CLASS_TEXT
                                        InputType/TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
                    :hint "email")]
      [:edit-text (assoc basis
                    :id ::pwdx2-et
                    :layout-margin-left [10 :dp]
                    :gravity android.view.Gravity/CENTER_HORIZONTAL
                    :input-type (bit-or InputType/TYPE_CLASS_TEXT
                                        InputType/TYPE_TEXT_VARIATION_PASSWORD)
                    :hint "password x2")]]
     [:linear-layout {:id ::buttons
                      :layout-below ::email-and-pwdx2
                      :layout-width :fill
                      :layout-margin-top [10 :dp]
                      :layout-margin-left [20 :dp]
                      :layout-margin-right [20 :dp]}
      [:button (assoc basis
                 :id ::signin-but
                 ;; :text "Wait, I got it"
                 :on-click (fn [w]
                             (let [a (.getContext w)
                                   state (.state a)]
                               (if (:signup-active? @state)
                                 (do (swap! state assoc :signup-active? false)
                                     (refresh-ui a))
                                 (login-via-input a)))))]
      [:button (assoc basis
                 :id ::signup-but
                 :layout-margin-left [30 :dp]
                 ;; :text "No account?"
                 :on-click (fn [w]
                             (let [a (.getContext w)
                                   state (.state a)]
                               (if (not (:signup-active? @state))
                                 (do (swap! state assoc :signup-active? true)
                                     (refresh-ui a))
                                 (register a)))))]]]))

(defn activity-ui [landscape?]
  [:relative-layout {:layout-width :fill
                     :layout-height :fill}
   [:linear-layout (cond-> {:id ::gus-logo
                            :orientation :vertical
                            :gravity :center
                            :layout-margin [10 :dp]}
                           landscape? (assoc :layout-center-vertical true)
                           (not landscape?) (assoc :layout-center-horizontal true))
    [:text-view {:id ::welcome-tv
                 :text "Welcome to 4clojure*!"
                 :text-size [22 :sp]}]
    [:image-view {:image #res/drawable :org.bytopia.foreclojure/foreclj-logo
                  :layout-height (traits/to-dimension (*a) (if landscape? [220 :dp] [320 :dp]))}]]
   (login-form landscape?)
   [:text-view {:text (Html/fromHtml "*This app is an unofficial client for <a href=\"http://4clojure.com\">4clojure.com</a>")
                :movement-method (LinkMovementMethod/getInstance)
                :text-size [14 :sp]
                :padding-right [5 :dp]
                :layout-align-parent-right true
                :layout-align-parent-bottom true}]])

(defactivity org.bytopia.foreclojure.LoginActivity
  :extends android.accounts.AccountAuthenticatorActivity
  :key :user
  :state (atom {})
  :on-create
  (fn [this bundle]
    (neko.activity/request-window-features! this :indeterminate-progress :no-title)
    ;; (.addFlags (.getWindow this) android.view.WindowManager$LayoutParams/FLAG_KEEP_SCREEN_ON)
    (let [;;this (*a)
          landscape? (= (.. this (getResources) (getConfiguration) orientation)
                        Configuration/ORIENTATION_LANDSCAPE)]
      (on-ui
        (safe-for-ui
         (set-content-view! this (activity-ui landscape?))
         (refresh-ui this))))
    ))
