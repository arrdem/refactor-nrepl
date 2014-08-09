(ns refactor-nrepl.artifacts
  (:require [clj-http.client :as http]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as transport]))

(def artifacts (atom {} :meta {:last-modified nil}))
(def millis-per-day (* 24 60 60 1000))

(defn get-artifacts-from-clojars! []
  (let [rdr (io/reader (java.net.URL. "https://clojars.org/repo/all-jars.clj"))
        add-lib (fn [line]
                  ;; each line looks like [some/lib "0.1"]
                  (let [lib (edn/read-string line)]
                    (swap! artifacts update-in [(str (first lib))] conj (second lib))))]
    (dorun (map add-lib (line-seq rdr)))))

(defn- stale-cache? []
  (or (empty? @artifacts)
      (neg? (- millis-per-day
               (- (-> artifacts meta :last-modified .getTime)
                  (-> (java.util.Date.) .getTime))))))

(defn- get-all-clj-artifacts! []
  "All the artifacts under org.clojure in mvn central"
  (let [search-url "http://search.maven.org/solrsearch/select?q=g:%22org.clojure%22+AND+p:%22jar%22&rows=2000&wt=json"
        search-result (http/get search-url {:as :json})]
    (map :a (-> search-result :body :response :docs))))

(defn- get-versions! [artifact]
  (->> (http/get (str "http://search.maven.org/solrsearch/select?"
                      "q=g:%22org.clojure%22+AND+a:%22"
                      artifact
                      "%22&core=gav&rows=200&wt=json")
                 {:as :json})
       :body
       :response
       :docs
       (map :v)
       doall))

(defn- collate-artifact-and-versions [artifact]
  (->> artifact
       get-versions!
       (vector (str "org.clojure/" artifact))))

(defn- add-artifact [[artifact versions]]
  (swap! artifacts update-in [artifact] (constantly versions)))

(defn- add-artifacts [artifacts]
  (->> artifacts
       (partition-all 2)
       (map #(future (->> %
                         (map collate-artifact-and-versions)
                         (map add-artifact)
                         dorun)))))

(defn- get-artifacts-from-mvn-central! []
  (-> (get-all-clj-artifacts!)
      (add-artifacts)))

(defn- update-artifact-cache! []
  (let [clojars-future (future (get-artifacts-from-clojars!))
        mvn-central-futures (get-artifacts-from-mvn-central!)]
    (map deref mvn-central-futures)
    @clojars-future)
  (alter-meta! artifacts update-in [:last-modified]
               (constantly (java.util.Date.))))

(defn- artifacts-list [{:keys [transport force] :as msg}]
  (when (or force (stale-cache?))
    (update-artifact-cache!))
  (let [names (->> @artifacts
                   keys
                   (interpose " ")
                   (apply str))]
    (transport/send transport (response-for msg :value names)))
  (transport/send transport (response-for msg :status :done)))

(defn- artifact-versions [{:keys [transport artifact] :as msg}]
  (let [versions (->> artifact (@artifacts) (interpose " ") (apply str))]
    (transport/send transport (response-for msg :value versions))
    (transport/send transport (response-for msg :status :done))))

(defn wrap-artifacts
  [handler]
  (fn [{:keys [op] :as msg}]
    (cond (= op "artifact-list") (artifacts-list msg)
          (= op "artifact-versions") (artifact-versions msg)
          :else
          (handler msg))))

(set-descriptor!
 #'wrap-artifacts
 {:handles
  {"artifact-list"
   {:doc "Returns a list of all the artifacts avilable on clojars, and a select few from mvn central.  The value is is cached for one day, or until the repl session is terminated."
    :requires {}
    :optional {"force" "which, if present, indicates whether we should force an update of the list of artifacts, rather than use the cache."}
    :returns {"status" "done"
              "value" "string containing artifacts, separated by spaces."}}
   "artifact-versions"
   {:doc "Get all the available versions for an artifact."
    :requires {"artifact" "the artifact whose versions we're interested in"}
    :optional {}
    :returns {"status" "done"
              "value" "string containing artifact versions, separated by spaces."}}}})
