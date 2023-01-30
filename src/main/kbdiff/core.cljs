(ns kbdiff.core
  (:require ["@qmk-helper/kle-serial" :as kle]
            [editscript.core :as e]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]))

;;; CLI utils
(def default-diff-file-path "kle-diff.json")
(def default-changed-key-color "#ffff00")
(def default-changed-key-text-color "#000000")
(def default-include-init-layouts false)
(def default-stack-axis "x")
(def default-gap 3)

(def cli-options
  [["-1" "--version1 path" "Path to the json layout file of the version 1"]
   ["-2" "--version2 path" "Path to the json layout file of the version 2"]
   ["-d" "--dest path" "Path to the resulting diff file"
    :default default-diff-file-path]
   ["-c" "--color value" "HEX value of the color for the changed key on diff"
    :default default-changed-key-color]
   ["-t" "--text-color value" "HEX value of the color for the text of changed key on diff"
    :default default-changed-key-text-color]
   ["-i" "--include-layouts" "Whether to include initial layouts in the diff"
    :default default-include-init-layouts]
   ["-a" "--axis value" "On which axis to stack layouts"
    :default default-stack-axis]
   ["-g" "--gap value" "Gap between stacked layouts"
    :default default-gap
    :parse-fn #(js/parseInt %)]
   ["-h" "--help"]])

(defn error-msg [errors]
  (str "Something went wrong:\n\n" (str/join \newline errors)))

(defn validate-args
  "Validate command line arguments. Either return a map indicating the program
  should exit (with an error message, and optional ok status), or a map
  indicating the action the program should take and the options provided."
  [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)
        {:keys [help version1 version2 dest color
                text-color include-layouts axis gap]}  options]
    (cond
      help
      {:exit-message summary :ok? true}
      errors
      {:exit-message (error-msg errors)}
      (and
        (nil? version1)
        (nil? version2))
      {:exit-message (error-msg ["You didn't privide versions of layout."])}
      (nil? version1)
      {:exit-message (error-msg ["You didn't provide the first version of layout."])}
      (nil? version2)
      {:exit-message (error-msg ["You didn't provide the second version of layout."])}
      (not (#{"x" "y"} axis))
      {:exit-message (error-msg ["Axis value can be only x or y."])}
      :else 
      {:version1 version1
       :version2 version2
       :include-layouts include-layouts
       :axis axis
       :gap gap
       :dest dest
       :color color
       :text-color text-color})))

(defn exit [status msg]
  (println msg)
  (.exit js/process status))

;;; FS utils
(def fs (js/require "fs"))

(defn node-slurp [path]
  (.readFileSync fs path "utf8"
                 (fn [err data]
                   (when err
                     (js/console.log err)))))

(defn node-mkdir
  [path]
  (.mkdir fs path {:recursive true}
          (fn [err data])))

(defn node-write-file
  [path data]
  (let [[file dir] (re-find #"(.*)/[^/]*" path)]
    (when (some? dir) (node-mkdir dir))
    (.writeFile fs path data "utf8"
                (fn [err data]
                  (if err
                    (js/console.log err)
                    (println (str "Created " path "!")))))))

;;; KLE utils
(defn kle-deserialize
  [kbd]
  (->> kbd (clj->js) (kle/deserialize)))

(defn kle-parse
  [s]
  (->> s (clj->js) (kle/parse)))

(defn kle-serialize
  [kbd]
  (->> kbd (clj->js) (kle/serialize)))

(defn kle-stringify
  [kbd]
  (->> kbd (clj->js) (kle/stringify)))

(defn obj->clj-map
  [obj]
  (-> obj
      js/JSON.stringify
      js/JSON.parse
      (js->clj :keywordize-keys true)))

(defn json->kbd
  [path]
  (->> path (node-slurp) (kle-parse)))

(defn kbd->json
  [path kbd]
  (->> kbd kle-stringify (node-write-file path)))

;;; DIFF
(defn get-diff
  [v1 v2]
  (let [v1 (-> v1 obj->clj-map :keys)
        v2 (-> v2 obj->clj-map :keys)]
    (->> [v1 v2]
         (apply e/diff)
         (e/get-edits))))

(defn get-changed-keys-idx
  [kle-diff]
  (->> kle-diff
       (map (comp first first))
       distinct))

;; FIXME in `release` build mode text-color is always "#ffffff"
(defn mark-changed-keys
  [v1 v2 color text-color]
  (let [kle-diff (get-diff v1 v2)
        changed-keys (->> kle-diff (map (comp first first)) distinct)
        diff (->> v2 kle-stringify kle-parse)]
    (doseq [k changed-keys]
      (let [key-obj (-> diff .-keys (get k))
            labels (-> key-obj .-labels js->clj)
            text-color (->> labels
                            (map #(when (some? %) text-color))
                            clj->js)]
        (set! (-> key-obj .-color) color)
        (set! (-> key-obj .-textColor) text-color)))
    diff))

(def ^:dynamic *merge-axis* nil)
(def ^:dynamic *merge-gap* nil)

(defn get-max-axis-value
  [kbd axis]
  (->> kbd
       (obj->clj-map)
       :keys
       (keep #(get % (keyword axis)))
       (apply max)))

(defn merge-two-kbds
  [k1 k2]
  (let [k1-max-axis-value (get-max-axis-value k1 *merge-axis*)
        [k1-keys k2-keys] (map #(js/goog.object.get % "keys") [k1 k2])]
    ;; Move k2 keys
    (doseq [k k2-keys
            :let [curr-value (js/goog.object.get k *merge-axis*)]]
      (js/goog.object.set k *merge-axis* (+ curr-value k1-max-axis-value *merge-gap*)))

    (js/goog.object.set k1 "keys" (clj->js (concat k1-keys k2-keys)))
    ;; return modified k1
    k1))

(defn add-layouts
  [v1 v2 diff]
  (reduce merge-two-kbds [v1 diff v2]))

;;; MAIN
(defn ^:export -main [& args]
  (let [{:keys [version1 version2 dest color text-color
                include-layouts axis gap exit-message ok?]}
        (validate-args args)]
    (if exit-message
       (exit (if ok? 0 1) exit-message)

       (let [v1 (json->kbd version1)
             v2 (json->kbd version2)]
         (as-> [v1 v2 color text-color] $
           (apply mark-changed-keys $)
           (if (true? include-layouts)
             (binding [*merge-axis* axis
                       *merge-gap* gap]
               (add-layouts v1 v2 $))
             $)
           (kbd->json dest $))))))

