(ns org.bytopia.foreclojure.db
  (:require [clojure.data.json :as json]
            [clojure.java.io :as jio]
            [clojure.string :as str]
            [neko.data.sqlite :as db]
            [neko.debug :refer [*a]]
            [neko.log :as log])
  (:import android.content.Context))

(def ^:private db-schema
  (let [nntext "text not null"]
    (db/make-schema
     :name "4clojure.db"
     :version 1
     :tables {:problems {:columns
                         {:_id          "integer primary key"
                          :title        nntext
                          :difficulty   nntext
                          :description  nntext
                          :restricted   nntext
                          :tests        nntext}}
              :users {:columns
                      {:_id      "integer primary key"
                       :username nntext
                       :password "blob"}}
              :solutions {:columns
                          {:_id "integer primary key"
                           :user_id "integer"
                           :problem_id "integer"
                           :is_solved "boolean"
                           :is_synced "boolean"
                           :code "text"}}})))

(def ^:private get-db-helper
  "Function that given an application context returns a database helper."
  (memoize (fn [^Context context]
             (db/create-helper (.getApplicationContext context) db-schema))))

(defn get-db
  "Returns a writeable database given an application context. If the argument is
  already a db, just returns it."
  [context-or-db]
  (if (instance? Context context-or-db)
    (db/get-database (get-db-helper context-or-db) :write)
    context-or-db))

;; (def db (get-db (*a :main)))

(defn db-empty?
  "Returns true if database hasn't been yet populated with any problems."
  [db]
  (zero? (db/query-scalar db ["count" :_id] :problems nil)))

;; (db-empty? db)

(defn problem-json->db
  "Transforms a problem map in JSON format into the one used by our SQLite
  database (and therefore whole application)."
  [json]
  {:pre [(map? json)]}
  {:_id (json "id")
   :title (json "title")
   :difficulty (json "difficulty")
   :description (json "description")
   ;; Restricted is a vector of strings. Let's serialize it in such way that we
   ;; later get a set of symbols in one swing.
   :restricted (->> (json "restricted")
                    (interpose " ")
                    str/join
                    (format "#{%s}"))
   ;; Tests is a vector of strings. Let's keep it this way.
   :tests (pr-str (json "tests"))})

;; (problem-json->db {"id" 13 "title" "Foo" "tests" ["(= __ true)" "(= __ false)"]})

(defn insert-problem [context-or-db json-map]
  (log/d "insert-problem()" "problem-id" (get (problem-json->db json-map) "_id"))
  (db/insert (get-db context-or-db) :problems (problem-json->db json-map)))

(defn populate-database
  "Inserts problems into database from the JSON file that is stored in assets."
  [^Context context, db]
  (db/transact db
    (with-open [stream (jio/reader (.open (.getAssets context) "data.json"))]
      (doseq [problem (json/read stream)]
        (insert-problem db problem)))))

;; (db/insert db :users {:username "@debug"})
;; (db/insert db :users {:username "@debug2"})
;; (db/insert db :solutions {:user_id 1, :problem_id 1,
;;                           :code "true", :is_solved true})
;; (db/insert db :solutions {:user_id 2, :problem_id 2,
;;                           :code "4", :is_solved true})

(defn get-last-problem-id
  "Returns the largest problem ID in the database."
  [db]
  (db/query-scalar db ["max" :_id] :problems nil))

;; (get-last-problem-id db)

(defn initialize
  "Spins up the database, populates if necessary, returns the last problem ID."
  [context]
  (let [db (get-db context)]
    (when (db-empty? db)
      (populate-database context db))
    (get-last-problem-id db)))

;; (time (initialize (*a :main)))

(defn get-problem [context i]
  (-> (get-db context)
      (db/query-seq :problems {:_id i})
      first
      (update-in [:tests] read-string)
      (update-in [:restricted] (comp set read-string))))

;; (get-problem (*a :main) 1)

(defn update-user [context-or-db username password-bytes]
  (let [db (get-db context-or-db)
        user-id (db/query-scalar db :_id :users {:username username})]
    (if user-id
      (db/update db :users {:password password-bytes} {:_id user-id})
      (db/insert db :users {:username username
                            :password password-bytes}))))

(defn get-user [context-or-db username]
  (first (db/query-seq (get-db context-or-db) :users {:username username})))

;; (get-user (*a :main) "@debug")

(defn get-solution
  [context-or-db username problem-id]
  (-> (get-db context-or-db)
      (db/query-seq [:solutions/_id :solutions/code
                     :solutions/is_solved :solutions/is_synced]
                    [:solutions :users]
                    {:users/_id :solutions/user_id
                     :solutions/problem_id problem-id
                     :users/username username})
      first))

;; (get-solution (*a :main) "testclient" 12)

(defn insert-solution [context username solution-map]
  (let [db (get-db context)
        user-id (db/query-scalar db :_id :users {:username username})]
    (db/insert db :solutions (assoc solution-map :user_id user-id))))

;; (insert-solution (*a :main) "@debug" {:problem_id 3, :is_solved true})

(defn update-solution
  "Updates user's solution to the problem. If the new solution is
  correct (:is_solved), force overwrite, otherwise only write the solution if
  the user haven't presented the correct solution to the current problem yet."
  [context username problem-id new-solution]
  (log/d "Update solution:" username problem-id new-solution)
  (let [db (get-db context)
        user-id (db/query-scalar db :_id :users {:username username})
        old-solution (get-solution db username problem-id)]
    (if old-solution
      (when (or (:is_solved new-solution)
                (not (:solutions/is_solved old-solution)))
        (db/update db :solutions new-solution
                   {:_id (:solutions/_id old-solution)}))
      (db/insert db :solutions (assoc new-solution
                                 :user_id user-id, :problem_id problem-id)))))

;; (update-solution (*a :main) "@debug" 1 {:code "true", :is_solved true})

(defn get-problems-cursor
  "Queries database for the problems to be displayed in the grid. Returns a
  Cursor object."
  [context username show-solved?]
  (let [db (get-db context)
        user-id (db/query-scalar db :_id :users {:username username})]
    (db/query
     db
     [:problems/_id :problems/title :problems/description :solutions/is_solved]
     (str "problems LEFT OUTER JOIN solutions ON solutions.problem_id = problems._id "
          "AND solutions.user_id = " user-id)
     (when-not show-solved? {:solutions/is_solved [:or false nil]}))))

;; (get-problems-cursor (*a :main) "testclient" true)

(defn get-solved-ids-for-user
  "Returns a set of solved IDs by the given user."
  [context username]
  (let [db (get-db context)
        user-id (db/query-scalar db :_id :users {:username username})]
    (->> (db/query-seq db [:problem_id] :solutions {:user_id user-id, :is_solved true})
         (map :problem_id)
         set)))

;; (get-solved-ids-for-user (*a :main) "@debug")

;; (db/query-seq (get-db (*a :main)) :solutions {:user_id 3})
