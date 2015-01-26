(ns org.bytopia.foreclojure.logic
  (:require clojure.walk))

(defn check-suggested-solution
  "Evaluates user code against problem tests. Returns a map of test numbers to
  the errors (map is empty if all all tests passed correctly). `restricted` is a
  set of symbols that are forbidden to use."
  [^String code, tests restricted]
  (reduce-kv
   (fn [err-map i ^String test]
     (if (empty? code)
       (assoc err-map i "Empty input is not allowed.")
       (try
         (let [code-form (binding [*read-eval* false]
                           (read-string (.replace test "__" code)))
               restricted (conj restricted 'eval)
               found-restricted (clojure.walk/postwalk
                                 (fn [f]
                                   (if (sequential? f)
                                     (some identity f)
                                     (restricted f)))
                                 code-form)]
           (if (nil? found-restricted)
             (if-not (eval code-form)
               (assoc err-map i "Unit test failed.")
               err-map)
             (assoc err-map i
                    (str "Not fair! Function is not allowed: "
                         found-restricted))))
         (catch Throwable e (assoc err-map i (str e))))))
   {} (vec tests)))

;; (check-suggested-solution "[1 (/ 1 0) 4]" (:problem-tests @state) #{})

