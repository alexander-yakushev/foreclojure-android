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
  (:import android.accounts.AbstractAccountAuthenticator
           android.accounts.AccountAuthenticatorActivity
           android.accounts.AccountManager
           android.content.Intent
           android.content.res.Configuration
           android.widget.EditText
           javax.crypto.Cipher
           javax.crypto.SecretKey
           javax.crypto.spec.SecretKeySpec))

(def secret-key (SecretKeySpec. (.getBytes KEY) "Blowfish"))

(defn- encrypt-pwd [password]
  (let [cipher (doto (Cipher/getInstance ALGORITHM)
                 (.init Cipher/ENCRYPT_MODE secret-key))]
    (.doFinal cipher (.getBytes password))))

(defn- decrypt-pwd [password-bytes]
  (let [cipher (doto (Cipher/getInstance ALGORITHM)
                 (.init Cipher/DECRYPT_MODE secret-key))]
    (String. (.doFinal cipher password-bytes))))

(defn set-user [a username password]
  (db/update-user a username (encrypt-pwd password)))

(defn lookup-user [a username]
  (update-in (db/get-user a username) [:password] decrypt-pwd))

(defn login-via-input [wdg]
  (let [a (.getContext ^android.view.View wdg)
        [user-et pwd-et] (find-views a ::user-et ::pwd-et)
        username (str (.getText ^EditText user-et))
        password (str (.getText ^EditText pwd-et))]
    (neko.log/d "username" username "password" password)
    (future
      (if-let [success? (api/login username password true)]
        (do (-> (neko.data/get-shared-preferences (*a) "4clojure" :private)
                .edit
                (neko.data/assoc! :last-user username)
                .commit)
            (set-user a username password)
            (.startActivity
             a (Intent. a (resolve 'org.bytopia.foreclojure.ProblemGridActivity)))
            (.finish a))
        (on-ui a (toast "Could not connect. Please check the correctness of your credentials."))))))

(defn login-via-saved [a username force?]
  (let [pwd (:password (lookup-user a username))]
    (api/login username pwd force?)))

(defn login-form-ui [landscape?]
  [:relative-layout {:layout-width :fill
                     :layout-height :fill}
   [:relative-layout {:layout-center-in-parent true}
    [:text-view {:id ::welcome-tv
                 :text "Welcome to 4clojure*!"
                 :layout-center-horizontal true
                 :text-size [24 :sp]}]
    [:image-view {:id ::gus-logo
                  :layout-below ::welcome-tv
                  :layout-center-horizontal true
                  :image #res/drawable :org.bytopia.foreclojure/foreclj-logo
                  :layout-height (if landscape? [180 :dp] [320 :dp])}]
    [:edit-text (cond-> {:id ::user-et
                         :layout-below ::gus-logo
                         :layout-width [200 :dp]
                         :gravity android.view.Gravity/CENTER_HORIZONTAL
                         :input-type (bit-or android.text.InputType/TYPE_CLASS_TEXT
                                             android.text.InputType/TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                         :hint "username"}
                        (not landscape?) (assoc :layout-center-horizontal true))]
    [:edit-text (cond-> {:id ::pwd-et
                         :layout-width [200 :dp]
                         :gravity android.view.Gravity/CENTER_HORIZONTAL
                         :input-type (bit-or android.text.InputType/TYPE_CLASS_TEXT
                                             android.text.InputType/TYPE_TEXT_VARIATION_PASSWORD)
                         :hint "password"}
                        landscape? (assoc :layout-below ::gus-logo
                                          :layout-to-right-of ::user-et)
                        (not landscape?) (assoc :layout-below ::user-et
                                                :layout-center-horizontal true))]
    [:button {:id ::login-but
              :layout-below ::pwd-et
              :layout-center-horizontal true
              :layout-margin-top [10 :dp]
              :text "Login"
              :on-click #'login-via-input}]]
   [:text-view {:text "*4clojure for Android is not affiliated with 4clojure.com"
                :text-size [10 :sp]
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
    (.addFlags (.getWindow this) android.view.WindowManager$LayoutParams/FLAG_KEEP_SCREEN_ON)
    (let [this (*a)
          landscape? (= (.. this (getResources) (getConfiguration) orientation)
                        Configuration/ORIENTATION_LANDSCAPE)]
      (on-ui
        (safe-for-ui
         (set-content-view! this (login-form-ui landscape?)))))))
