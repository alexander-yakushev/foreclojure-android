(ns org.bytopia.foreclojure.utils
  (:require [neko.activity :as a]
            [neko.find-view :refer [find-view]]
            [neko.listeners.view :refer [on-click-call]]
            [neko.notify :refer [toast]]
            [neko.resource :refer [get-string]]
            [neko.threading :refer [on-ui]]
            [neko.ui :as ui]
            [neko.ui.mapping :refer [defelement]]
            [neko.ui.menu :as menu]
            [neko.ui.traits :as traits]
            [neko.-utils :as u])
  (:import android.app.Activity
           android.view.View
           android.support.v4.widget.SwipeRefreshLayout$OnRefreshListener
           android.support.design.widget.Snackbar
           android.support.v4.view.ViewCompat
           [android.support.v4.widget DrawerLayout DrawerLayout$DrawerListener]
           [android.support.v7.app AppCompatActivity ActionBarDrawerToggle]
           java.util.HashMap))

(defn ellipsize [s max-length]
  (let [lng (count s)]
    (if (> lng max-length)
      (str (subs s 0 max-length) "…") s)))

(defmacro long-running-job
  "Runs body in a future, initializing progress with `progress-start`
  expression, and ending it with `progress-stop`."
  [progress-start progress-stop & body]
  (let [asym (with-meta 'a {:tag android.app.Activity})]
    `(future
       (on-ui ~progress-start)
       (try ~@body
            (catch Exception ex# (on-ui (toast (str ex#))))
            (finally (on-ui ~progress-stop))))))

(defn on-refresh-listener-call
  [callback swipe-layout]
  (reify SwipeRefreshLayout$OnRefreshListener
    (onRefresh [_]
      (u/call-if-nnil callback swipe-layout))))

(defn snackbar
  ([view-or-activity text duration]
   (snackbar view-or-activity text duration nil nil))
  ([view-or-activity text duration action-text action-callback]
   (let [sb (Snackbar/make ^View (if (instance? Activity view-or-activity)
                                   (a/get-decor-view view-or-activity)
                                   view-or-activity)
                           (get-string text)
                           duration)]
     (when action-text
       (.setAction sb action-text (on-click-call action-callback)))
     (.show sb))))

(neko.ui.mapping/defelement :drawer-layout
  :classname android.support.v4.widget.DrawerLayout
  :inherits :view-group
  :traits [:drawer-toggle])

(neko.ui.mapping/defelement :navigation-view
  :classname android.support.design.widget.NavigationView
  :inherits :frame-layout
  :traits [:navbar-menu :navbar-header-view])

(neko.ui.traits/deftrait :drawer-layout-params
  "docs"
  {:attributes (concat (deref #'neko.ui.traits/margin-attributes)
                       [:layout-width :layout-height
                        :layout-weight :layout-gravity])
   :applies? (= container-type :drawer-layout)}
  [^View wdg, {:keys [layout-width layout-height layout-weight layout-gravity]
               :as attributes}
   {:keys [container-type]}]
  (let [^int width (->> (or layout-width :wrap)
                        (neko.ui.mapping/value :layout-params)
                        (neko.ui.traits/to-dimension (.getContext wdg)))
        ^int height (->> (or layout-height :wrap)
                         (neko.ui.mapping/value :layout-params)
                         (neko.ui.traits/to-dimension (.getContext wdg)))
        weight (or layout-weight 0)
        params (android.support.v4.widget.DrawerLayout$LayoutParams. width height weight)]
    (#'neko.ui.traits/apply-margins-to-layout-params (.getContext wdg) params attributes)
    (when layout-gravity
      (set! (. params gravity)
            (neko.ui.mapping/value :layout-params layout-gravity :gravity)))
    (.setLayoutParams wdg params)))

(neko.ui.traits/deftrait :drawer-toggle
  "docs"
  {:attributes [:drawer-open-text :drawer-closed-text :drawer-indicator-enabled
                :on-drawer-closed :on-drawer-opened
                :on-drawer-slide :disable-anim :disable-anim-id]}
  [^DrawerLayout wdg, {:keys [drawer-open-text drawer-closed-text
                              drawer-indicator-enabled
                              on-drawer-opened on-drawer-closed
                              on-drawer-slide disable-anim disable-anim-id]}
   {:keys [^View id-holder]}]
  (let [toggle (proxy [ActionBarDrawerToggle DrawerLayout$DrawerListener]
                   [^android.app.Activity (.getContext wdg)
                    wdg
                    ^int (or drawer-open-text android.R$string/untitled)
                    ^int (or drawer-closed-text android.R$string/untitled)]
                 (onDrawerOpened [view]
                   (neko.-utils/call-if-nnil on-drawer-opened view))
                 (onDrawerClosed [view]
                   (neko.-utils/call-if-nnil on-drawer-closed view))
                 (onDrawerSlide [view slide-offset]
                   (let [wanted-slide-offset
                         (if (or disable-anim
                                 (and disable-anim-id
                                      (= (.getId view)
                                         (.getId (find-view (.getContext wdg)
                                                            disable-anim-id)))))
                           0
                           slide-offset)]
                     (proxy-super onDrawerSlide view wanted-slide-offset)
                     (neko.-utils/call-if-nnil on-drawer-slide view slide-offset))))]
    (.setDrawerIndicatorEnabled toggle (boolean drawer-indicator-enabled))
    (.setDrawerListener wdg toggle)
    (when id-holder
      (.put ^HashMap (.getTag id-holder) :neko.ui/drawer-toggle toggle))))

(neko.ui.mapping/defelement :swipe-refresh-layout
  :classname android.support.v4.widget.SwipeRefreshLayout
  :traits [:on-refresh])

(neko.ui.traits/deftrait :on-refresh
  "docs "
  [^android.support.v4.widget.SwipeRefreshLayout wdg, {:keys [on-refresh]} _]
  (.setOnRefreshListener wdg (on-refresh-listener-call on-refresh wdg)))

(neko.ui.traits/deftrait :elevation
  "docs "
  [^View wdg, {:keys [elevation]} _]
  (ViewCompat/setElevation wdg (neko.ui.traits/to-dimension
                                (.getContext wdg) elevation)))

(neko.ui.traits/deftrait :navbar-header-view
  "docs "
  {:attributes [:header]}
  [^android.support.design.widget.NavigationView wdg, {:keys [header]} opts]
  (.addHeaderView wdg (ui/make-ui-element (.getContext wdg) header opts)))

(neko.ui.traits/deftrait :navbar-menu
  "docs "
  {:attributes [:menu]}
  [^android.support.design.widget.NavigationView wdg, {:keys [menu]} _]
  (menu/make-menu (.getMenu wdg) menu))

(neko.ui.mapping/add-trait! :view :drawer-layout-params)
(neko.ui.mapping/add-trait! :view :elevation)
(neko.ui.mapping/add-trait! :swipe-refresh-layout :drawer-layout-params)

(swap! (deref #'neko.ui.mapping/reverse-mapping) assoc android.widget.ProgressBar :progress-bar)
