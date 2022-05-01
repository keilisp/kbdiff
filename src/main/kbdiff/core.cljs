(ns kbdiff.core
  (:require ["@qmk-helper/kle-serial" :as kle]
            [editscript.core :as e]))

(def fs (js/require "fs"))
(def changed-key-color "#ffff00")
(def changed-key-text-color "#000000")

(defn kle-deserialize
  [kbd]
  (->> kbd
       (clj->js)
       (kle/deserialize)))

(defn kle-parse
  [s]
  (->> s
       (clj->js)
       (kle/parse)))

(defn kle-serialize
  [kbd]
  (->> kbd
       (clj->js)
       (kle/serialize)))

(defn kle-stringify
  [kbd]
  (->> kbd
       (clj->js)
       (kle/stringify)))

(defn node-slurp [path]
  (.readFileSync fs path "utf8"
                 (fn [err data]
                   (when err
                     (js/console.log err)))))

(defn node-write
  [path data]
  (.writeFile fs path data "utf8"
              (fn [err data]
                (when err
                  (js/console.log err)))))

(defn obj->clj-map
  [obj]
  (-> obj
      js/JSON.stringify
      js/JSON.parse
      (js->clj :keywordize-keys true)))

(defn json->kle
  [path]
  (->> path
       (node-slurp)
       (kle-parse)))

(defn kle->json
  [path kbd]
  (->> kbd
       kle-stringify
       (node-write path)))

(defn ^:export init []
  (prn "TODO")
  #_(->> [old-kle new-kle]
       (apply mark-changed-keys)
       (kle->json "kle-diff.json")))

(defn get-diff
  [old new]
  (let [old (-> old obj->clj-map :keys)
        new (-> new obj->clj-map :keys)]
    (->> [old new]
         (apply e/diff)
         (e/get-edits))))

(defn get-changed-keys-idx
  [kle-diff]
  (->> kle-diff
       (map (comp first first))
       distinct))

(defn mark-changed-keys
  [old new]
  (let [kle-diff (get-diff old new)
        changed-keys (->> kle-diff (map (comp first first)) distinct)]
    (doseq [k changed-keys]
      (let [key-obj (-> new .-keys (get k))
            labels (-> key-obj .-labels js->clj)
            text-color (clj->js (map #(when (some? %) changed-key-text-color) labels))]
        (set! (-> key-obj .-color) changed-key-color)
        (set! (-> key-obj .-textColor) text-color)))
    new))
