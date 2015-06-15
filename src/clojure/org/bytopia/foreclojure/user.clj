(ns org.bytopia.foreclojure.user
  (:require clojure.set
            [neko.activity :refer [defactivity set-content-view!]]
            neko.data
            neko.data.shared-prefs
            [neko.debug :refer [*a]]
            [neko.find-view :refer [find-view find-views]]
            [neko.notify :refer [toast]]
            [neko.threading :refer [on-ui]]
            neko.ui.adapters
            [neko.ui :as ui]
            [neko.ui.mapping :refer [defelement]]
            [neko.ui.menu :as menu]
            neko.resource
            [neko.ui.traits :as traits]
            [org.bytopia.foreclojure
             [db :as db]
             [api :as api]
             [utils :refer [long-running-job]]])
  (:import [android.app ProgressDialog Activity]
           android.content.Intent
           android.content.res.Configuration
           android.text.Html
           android.text.InputType
           android.view.Gravity
           android.view.View
           android.widget.EditText
           javax.crypto.Cipher
           javax.crypto.SecretKey
           javax.crypto.spec.SecretKeySpec
           [org.bytopia.foreclojure BuildConfig SafeLinkMethod]))

(neko.resource/import-all)

(def ^SecretKeySpec secret-key
  (SecretKeySpec. (.getBytes BuildConfig/ENC_KEY)
                  BuildConfig/ENC_ALGORITHM))

(defn- encrypt-pwd [^String password]
  (let [cipher (doto (Cipher/getInstance BuildConfig/ENC_ALGORITHM)
                 (.init Cipher/ENCRYPT_MODE secret-key))]
    (.doFinal cipher (.getBytes password))))

(defn- decrypt-pwd [^bytes password-bytes]
  (let [cipher (doto (Cipher/getInstance BuildConfig/ENC_ALGORITHM)
                 (.init Cipher/DECRYPT_MODE secret-key))]
    (String. (.doFinal cipher password-bytes))))

(defn set-user-db [a username password]
  (db/update-user a username (encrypt-pwd password)))

(defn lookup-user [a username]
  (update-in (db/get-user a username) [:password] decrypt-pwd))

(defn set-last-user [a username]
  (-> (neko.data.shared-prefs/get-shared-preferences a "4clojure" :private)
      .edit
      (neko.data.shared-prefs/put :last-user username)
      .commit))

(defn clear-last-user [a]
  (-> (neko.data.shared-prefs/get-shared-preferences a "4clojure" :private)
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
          (on-ui a (toast "Could not sign in. Please check the correctness of your credentials." :short)))
        (finally (on-ui (.dismiss progress)))))))

(defn login-via-saved [a username force?]
  (let [pwd (:password (lookup-user a username))]
    (api/login username pwd force?)))

(defn register [^Activity a]
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
            (on-ui a (toast error))))
        (catch Exception ex (on-ui (toast (str "Exception raised: " ex))))
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

(defn login-form [where]
  (let [basis {:layout-width 0, :layout-weight 1}
        basis-edit (assoc basis :ime-options android.view.inputmethod.EditorInfo/IME_FLAG_NO_EXTRACT_UI)]
    [:linear-layout {where ::gus-logo
                     :orientation :vertical
                     :layout-width :fill
                     :layout-height :fill
                     :gravity :center}
     [:linear-layout {:layout-width :fill
                      :layout-margin [10 :dp]}
      [:edit-text (assoc basis-edit
                    :id ::user-et
                    :gravity Gravity/CENTER_HORIZONTAL
                    :input-type (bit-or InputType/TYPE_CLASS_TEXT
                                        InputType/TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                    :hint "username")]
      [:edit-text (assoc basis-edit
                    :id ::pwd-et
                    :layout-margin-left [10 :dp]
                    :gravity Gravity/CENTER_HORIZONTAL
                    :input-type (bit-or InputType/TYPE_CLASS_TEXT
                                        InputType/TYPE_TEXT_VARIATION_PASSWORD)
                    :hint "password")]]
     [:linear-layout {:id ::email-and-pwdx2
                      ;; :layout-below ::user-and-pwd
                      :layout-width :fill
                      :layout-margin [10 :dp]}
      [:edit-text (assoc basis-edit
                    :id ::email-et
                    :gravity Gravity/CENTER_HORIZONTAL
                    :input-type (bit-or InputType/TYPE_CLASS_TEXT
                                        InputType/TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
                    :hint "email")]
      [:edit-text (assoc basis-edit
                    :id ::pwdx2-et
                    :layout-margin-left [10 :dp]
                    :gravity android.view.Gravity/CENTER_HORIZONTAL
                    :input-type (bit-or InputType/TYPE_CLASS_TEXT
                                        InputType/TYPE_TEXT_VARIATION_PASSWORD)
                    :hint "password x2")]]
     [:linear-layout {:layout-width :fill
                      :layout-margin-top [10 :dp]
                      :layout-margin-left [20 :dp]
                      :layout-margin-right [20 :dp]}
      [:button (assoc basis
                 :id ::signin-but
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
                 :on-click (fn [^View w]
                             (let [a (.getContext w)
                                   state (.state a)]
                               (if (not (:signup-active? @state))
                                 (do (swap! state assoc :signup-active? true)
                                     (refresh-ui a))
                                 (register a)))))]]]))

(defn activity-ui [landscape?]
  [:scroll-view {:layout-width :fill
                 :layout-height :fill
                 :fill-viewport true}
   [:relative-layout {:layout-width :fill
                      :layout-height :fill}
    [:linear-layout (cond-> {:id ::gus-logo
                             :orientation :vertical
                             :gravity :center
                             :layout-margin [10 :dp]}
                            landscape? (assoc :layout-center-vertical true)
                            (not landscape?) (assoc :layout-center-horizontal true))
     [:text-view {:id ::welcome-tv
                  :text "Welcome to 4Clojure*!"
                  :text-size [22 :sp]}]
     [:image-view {:image R$drawable/foreclj_logo
                   :layout-height (traits/to-dimension (*a) (if landscape? [250 :dp] [320 :dp]))}]
     [:text-view {:text (Html/fromHtml "*This is an unofficial client for <a href=\"http://4clojure.com\">4clojure.com</a>")
                  :movement-method (SafeLinkMethod/getInstance)
                  :text-size [14 :sp]
                  :padding-right [5 :dp]
                  :link-text-color (android.graphics.Color/rgb 0 0 139)}]]
    (login-form (if landscape?
                  :layout-to-right-of
                  :layout-below))]])

(defactivity org.bytopia.foreclojure.LoginActivity
  :key :user
  :features [:indeterminate-progress :no-title]

  (onCreate [this bundle]
    (.superOnCreate this bundle)
    (neko.debug/keep-screen-on this)
    (.. this (getWindow) (setSoftInputMode android.view.WindowManager$LayoutParams/SOFT_INPUT_STATE_HIDDEN))
    (let [this (*a)
          landscape? (= (.. this (getResources) (getConfiguration) orientation)
                        Configuration/ORIENTATION_LANDSCAPE)]
      (on-ui
        (set-content-view! this (activity-ui landscape?))
        (refresh-ui this)))
    ))
