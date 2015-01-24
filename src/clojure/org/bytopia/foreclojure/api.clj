(ns org.bytopia.foreclojure.api
  "Functions that interact with 4clojure API or fetch data directly."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as jio])
  (:import android.util.Xml
           java.io.FileNotFoundException
           java.io.StringReader
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

(defn problem-url->id [url]
  (Integer/parseInt (second (re-matches #".*/problem/(\d+)" url))))

(defmacro ^:private tag?
  "Helper macro for parsing. Locals `parser` and `event-type` are presumed to be
  bound."
  [start-or-end name & attrs]
  `(and (= ~'event-type ~(if (= start-or-end 'start)
                           'XmlPullParser/START_TAG 'XmlPullParser/END_TAG))
        (= (.getName ~'parser) ~name)
        ~@(for [[k v] (partition 2 attrs)]
            `(= (.getAttributeValue ~'parser nil ~k) ~v))))

(defn- parse-problems-page
  "Because parsing HTML page is the only way to get which problems the user have
  solved (and also how many problems are there)."
  [page-str]
  (let [^XmlPullParserFactory factory (doto (XmlPullParserFactory/newInstance)
                                        (.setValidating false)
                                        (.setFeature Xml/FEATURE_RELAXED true))
        ^XmlPullParser parser (doto (.newPullParser factory)
                                (.setInput (StringReader. page-str)))]
    (loop [solved #{},inside-problem-table false
           inside-titlelink false, problem-id nil]
      (let [event-type (.next parser)]
        (cond
         (or (= event-type XmlPullParser/END_DOCUMENT)
             (and inside-problem-table
                  (tag? end "table")))
         {:solved-ids solved
          :last-id problem-id}

         (tag? start "table", "id" "problem-table")
         (recur solved true false nil)

         (and inside-problem-table
              (tag? start "td", "class" "titlelink"))
         (recur solved true true nil)

         (and inside-titlelink
              (tag? start "a"))
         (recur solved true false
                (problem-url->id (.getAttributeValue parser nil "href")))

         (and inside-problem-table
              (tag? start "img"))
         (recur (if (= (.getAttributeValue parser nil "alt") "completed")
                  (conj solved problem-id) solved)
                true false problem-id)

         :else (recur solved inside-problem-table
                      inside-titlelink problem-id))))))

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
    (let [resp (http-post (get-http-client)
                          {:url "http://www.4clojure.com/login"
                           :form-params {"user" username, "pwd" password}})
          success? (= (:redirect resp) "/problems")]
      (when-not success?
        ;; Don't keep the faulty cookie.
        (.clear (.getCookieStore (get-http-client))))
      success?)))

;; (login "abc" "abc")

(defn fetch-problem
  "Given a problem ID requests it from 4clojure.com using REST API. Returns
  parsed JSON map, or nil if problem is not found."
  [id]
  (try
    (-> (str "http://www.4clojure.com/api/problem/" id)
        slurp
        json/read-str)
    (catch FileNotFoundException e nil)))

;; (fetch-problem 1)

(defn fetch-solved-problem-ids
  "Returns a map, where `:solved-ids` is a set of problem IDs solved by the
  current user; and `:last-id` is a biggest problem ID in the database."
  []
  (let [resp (http-get (get-http-client)
                       {:url "http://www.4clojure.com/problems"})]
    (parse-problems-page (:body resp))))

;; (fetch-solved-problem-ids)

;; (defn- fix-tests
;;   "Takes a problem map and turns `:tests` strings into Clojure code."
;;   [problem]
;;   (update-in problem [:tests]
;;              #(pr-str (map read-string %))))
