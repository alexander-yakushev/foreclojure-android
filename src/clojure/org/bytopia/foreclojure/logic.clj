(ns org.bytopia.foreclojure.logic
  (:require clojure.walk)
  (:import [java.util.concurrent FutureTask TimeUnit TimeoutException]))

(def ^:const timeout 3000)

(defn thunk-timeout
  "Takes a function and waits for it to finish executing for the predefined
  number of milliseconds. Stolen from Clojail and mutilated."
  [thunk]
  (let [task (FutureTask. thunk)
        thread (Thread. task)]
    (try
      (.start thread)
      (.get task timeout TimeUnit/MILLISECONDS)
      (catch TimeoutException e
        (.cancel task true)
        (.interrupt thread)
        (throw (TimeoutException. "Execution timed out.")))
      (catch Exception e
        (.cancel task true)
        (.interrupt thread)
        (throw e)))))

;; Poor man's clojailing here, of course it isn't enough.
;;
(def ^:private forbidden-symbols
  #{'eval 'resolve 'read 'read-string 'throw 'ns 'in-ns 'require 'use 'refer})

(defn check-suggested-solution
  "Evaluates user code against problem tests. Returns a map of test numbers to
  the errors (map is empty if all tests passed correctly). `restricted` is a set
  of symbols that are forbidden to use."
  [^String code, tests restricted]
  (reduce-kv
   (fn [err-map i ^String test]
     (if (empty? code)
       (assoc err-map i "Empty input is not allowed.")
       (try
         (let [code-form (binding [*read-eval* false]
                           (read-string (.replace test "__" code)))
               found-restricted (clojure.walk/postwalk
                                 (fn [f]
                                   (if (sequential? f)
                                     (some identity f)
                                     (or (forbidden-symbols f)
                                         (restricted f))))
                                 code-form)]
           (if (nil? found-restricted)
             (let [result (thunk-timeout (fn [] (eval code-form)))]
               (if result
                 err-map
                 (assoc err-map i "Unit test failed.")))
             (assoc err-map i
                    (str "Form is not allowed: "
                         found-restricted))))
         (catch Throwable e (assoc err-map i (str e))))))
   {} (vec tests)))

;; (check-suggested-solution "[1 (/ 1 0) 4]" (:problem-tests @state) #{})
;; (check-suggested-solution "6" ["(= (- 10 (* 2 3)) __)"] #{})
;; (check-suggested-solution
;;  "(loop [] (neko.log/d \"From Inside!\")
;; (Thread/sleep 500)
;; (recur))" ["(= __ true)"] #{})
