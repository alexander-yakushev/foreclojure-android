(ns org.bytopia.foreclojure.api
  "Functions that interact with 4clojure API or fetch data directly."
  (:require [clojure.data.json :as json])
  (:import android.app.Activity
           android.util.Xml
           java.io.FileNotFoundException
           java.io.StringReader
           java.net.InetAddress
           org.apache.http.client.RedirectHandler
           org.apache.http.client.entity.UrlEncodedFormEntity
           [org.apache.http.client.methods HttpGet HttpPost]
           org.apache.http.cookie.Cookie
           org.apache.http.impl.client.DefaultHttpClient
           org.apache.http.message.BasicNameValuePair
           org.apache.http.params.CoreProtocolPNames
           [org.xmlpull.v1 XmlPullParser XmlPullParserFactory]))

;;; Poor man's HTTP client
;; We roll out our own basic HTTP client wrapper because Apache HTTP lib on
;; Android differs. This also frees us of few extra dependencies. The client is
;; absolutely incomplete as we implement only things we need to communicate with
;; 4clojure.

(defn network-connected? []
  (try
    (not= (InetAddress/getByName "4clojure.com") "")
    (catch Exception _ false)))

(def ^:private get-http-client
  "Memoized function that returns an instance of HTTP client when called."
  (memoize (fn []
             (let [client (DefaultHttpClient.)]
               ;; Don't follow redirects
               (.setRedirectHandler
                client (proxy [RedirectHandler] []
                         (getLocationURI [response context] nil)
                         (isRedirectRequested [response context] false)))
               client))))

(defn http-post
  "Sends a synchronous POST request."
  ([data]
   (http-post (DefaultHttpClient.) data))
  ([client {:keys [url form-params]}]
   (let [request (HttpPost. url)
         ;; TODO: Support headers
         _ (when form-params
             (.setEntity request (UrlEncodedFormEntity.
                                  (for [[k v] form-params]
                                    (BasicNameValuePair. k v))
                                  "UTF-8")))
         response (.execute client request)]
     {:status (.getStatusLine response)
      :body (slurp (.getContent (.getEntity response)))
      :redirect (when-let [loc (.getLastHeader response "Location")]
                  (.getValue loc))})))

(defn http-get
  "Sends a synchronous GET request."
  ([data]
   (http-get (DefaultHttpClient.) data))
  ([client {:keys [url]}]
   (let [request (HttpGet. url)
         response (.execute client request)]
     {:status (.getStatusLine response)
      :body (slurp (.getContent (.getEntity response)))})))

;;; XML parser
;; Again, we use a native XmlPullParser to make things faster and save
;; dependencies.

(defn- problem-url->id [url]
  (Integer/parseInt (second (re-matches #".*/problem/(\d+)" url))))

(defn- problem-id->url [id]
  (str "http://www.4clojure.com/problem/" id))

(defn- problem-id->api-url [id]
  (str "http://www.4clojure.com/api/problem/" id))

(defmacro ^:private tag?
  "Helper macro for parsing. Locals `parser` and `event-type` are presumed to be
  bound."
  [start-or-end name & attrs]
  `(and (= ~'event-type ~(if (= start-or-end 'start)
                           'XmlPullParser/START_TAG 'XmlPullParser/END_TAG))
        (= (.getName ~'parser) ~name)
        ~@(for [[k v] (partition 2 attrs)]
            `(= (.getAttributeValue ~'parser nil ~k) ~v))))

(defn- ^XmlPullParser html-parser
  [page-str]
  (let [^XmlPullParserFactory factory (doto (XmlPullParserFactory/newInstance)
                                        (.setValidating false)
                                        (.setFeature Xml/FEATURE_RELAXED true))]
    (doto (.newPullParser factory)
      (.setInput (StringReader. page-str)))))

(defn- parse-problems-page
  "Because parsing HTML page is the only way to get which problems the user have
  solved (and also how many problems are there). `last-known-id` is the latest
  problem we know about and already fetched."
  [page-str last-known-id]
  (let [parser (html-parser page-str)]
    (loop [solved (), new (), inside-problem-table false
           inside-titlelink false, problem-id nil]
      (let [event-type (.next parser)]
        (cond
         (or (= event-type XmlPullParser/END_DOCUMENT)
             (and inside-problem-table
                  (tag? end "table")))
         {:solved-ids (set solved)
          :new-ids (sort new)}

         (tag? start "table", "id" "problem-table")
         (recur solved new true false nil)

         (and inside-problem-table
              (tag? start "td", "class" "titlelink"))
         (recur solved new true true nil)

         (and inside-titlelink
              (tag? start "a"))
         (let [id (problem-url->id (.getAttributeValue parser nil "href"))]
           (recur solved (if (> id last-known-id)
                           (conj new id) new)
                  true false id))

         (and inside-problem-table
              (tag? start "img"))
         (recur (if (= (.getAttributeValue parser nil "alt") "completed")
                  (conj solved problem-id) solved)
                new true false problem-id)

         :else (recur solved new inside-problem-table
                      inside-titlelink problem-id))))))

(defn- parse-problem-edit-page
  "Parse HTML page to get the existing solution of the problem by current user."
  [page-str]
  (let [parser (html-parser page-str)]
    (loop [in-text-area false]
      (let [event-type (.next parser)]
        (cond
         (= event-type XmlPullParser/END_DOCUMENT) nil

         (tag? start "textarea", "id" "code-box") (recur true)

         (and in-text-area
              (= event-type XmlPullParser/TEXT))
         (.getText parser)

         (and in-text-area
              (tag? end "textarea"))
         (recur false)

         :else (recur in-text-area))))))

;;; API interaction

(defn logged-in?
  "Checks if current HTTP client is logged in."
  []
  (not (empty? (.getCookies (.getCookieStore (get-http-client))))))

;; (logged-in?)

(defn login
  "Signs into 4clojure. Returns true if the login is successful."
  [username password force-relogin?]
  (if (and (logged-in?) (not force-relogin?))
    true
    (try
      (let [resp (http-post (get-http-client)
                              {:url "http://www.4clojure.com/login"
                               :form-params {"user" username, "pwd" password}})
              success? (= (:redirect resp) "/problems")]
          (when-not success?
            ;; Don't keep the faulty cookie.
            (.clear (.getCookieStore (get-http-client))))
          success?)
      (catch Exception ex false))))

;; (login "foo" "bar")

(defn fetch-problem
  "Given a problem ID requests it from 4clojure.com using REST API. Returns
  parsed JSON map, or nil if problem is not found."
  [id]
  (try
    (-> (problem-id->api-url id)
        slurp
        json/read-str)
    (catch FileNotFoundException e nil)))

;; (fetch-problem 23)

(defn fetch-user-solution
  "Given a problem ID fetches its solution submitted by the current user. If
  user haven't solved the problem returns empty string."
  [id]
  (let [resp (http-get (get-http-client)
                       {:url (problem-id->url id)})]
    (parse-problem-edit-page (:body resp))))

;; (fetch-user-solution 11)

(defn fetch-solved-problem-ids
  "Given ID of the last problem we know, returns a map, where `:solved-ids` is a
  set of problem IDs solved by the current user; and `:new-ids` is a list of new
  problem IDs yet unfetched by us."
  [last-known-id]
  (let [resp (http-get (get-http-client)
                       {:url "http://www.4clojure.com/problems"})]
    (parse-problems-page (:body resp) last-known-id)))

;; (fetch-solved-problem-ids 150)

(defn submit-solution
  "Posts a solution to a problem with given ID. Presumes user to be logged in.

  Returns true if the solution is correct, although the opposite should never
  happen since we check solutions locally prior to sending them."
  [problem-id code]
  (-> (http-post (get-http-client)
                 {:url (str "http://www.4clojure.com/rest/problem/" problem-id)
                  :form-params {"id" (str problem-id), "code" (str code)}})
      :body json/read-str
      (get "error") empty?))

;; (submit-solution 11 '(/ 1 0))
;; (submit-solution 11 '[:b 2])
