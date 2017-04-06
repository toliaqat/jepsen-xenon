
(ns jepsen.xenon
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clj-http.client :as httpclient]
            [verschlimmbesserung.core :as v]
            [slingshot.slingshot :refer [try+]]
            [potemkin :refer [definterface+]]
            [knossos.model :as model]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [client :as client]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [nemesis :as nemesis]
                    [core :as jepsen]
                    [tests :as tests]
                    [util :as util :refer [timeout]]
                    [independent :as independent]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.net  :as net]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(def dir "/opt/xenon")
(def binary "/usr/bin/java")
(def logfile (str dir "/xenon.log"))
(def pidfile (str dir "/xenon.pid"))

(defn node-url
  "An HTTP url for connecting to a node on a particular port."
  [node port]
  (str "http://" (name node) ":" port))

(defn peer-url
  "The HTTP url for other peers to talk to a node."
  [node]
  (node-url node 8000))

(defn client-url
  "The HTTP url clients use to talk to a node."
  [node]
  (node-url node 8000))

(defn initial-cluster
  "Constructs an initial cluster string for a test, like
  \"foo=foo:2380,bar=bar:2380,...\""
  [test]
  (->> (:nodes test)
       (map (fn [node]
              (str (peer-url node))))
       (str/join ",")))

(defn examples-url
  "The HTTP url clients use to talk to a node."
  [node]
  (str (node-url node 8000) "/core/examples"))

(defn x-post
  [node test key value]
  (httpclient/post (str (examples-url node)) {:form-params {:name (str value) :documentSelfLink (str key )} :content-type :json})
     )

(defn x-put
  [node test key value]
  (try
  (httpclient/put (str (examples-url node) "/" key ) {:form-params {:name (str value)} :content-type :json})
  (catch Exception e
     (x-post node test key value))))

(defn x-get
  [node key]
  (:name (json/read-str (:body (httpclient/get (str (examples-url node) "/" key) {:accept :json})) :key-fn keyword )))

(defn x-query
  [node key]
  (:name (first (vals  (:documents  (json/read-str (:body (httpclient/get (str (examples-url node) "/?$filter=documentSelfLink eq '/core/examples/" key "'") {:accept :json} )) :key-fn keyword) )))))

(defn post-node-group-join
  [self-node test]
  (info self-node "triggering join request")
       (map (fn [node]
         (info self-node node "joing nodes")
         (httpclient/post
                (str "http://" (name self-node) ":8000/core/node-groups/default")
           {
            :content-type :json
            :form-params {
                     :kind (str "com:vmware:xenon:services:common:NodeGroupService:JoinPeerRequest")
                     :memberGroupReference (str "http://"  (name node) ":8000/core/node-groups/default")
                     :membershipQuorum 1
                     :localNodeOptions [ "PEER" ] }})) (:nodes test) ))

(defn parse-long
  "Parses a string to a Long. Passes through `nil`."
  [s]
  (when s (Long/parseLong s)))


(definterface+ Model
  (step [model op]
        "The job of a model is to *validate* that a sequence of operations
        applied to it is consistent. Each invocation of (step model op)
        returns a new state of the model, or, if the operation was
        inconsistent with the model's state, returns a (knossos/inconsistent
        msg). (reduce step model history) then validates that a particular
        history is valid, and returns the final state of the model.
        Models should be a pure, deterministic function of their state and an
        operation's :f and :value."))

(defrecord Inconsistent [msg]
  Model
  (step [this op] this)

  Object
  (toString [this] msg))

(defn inconsistent
  "Represents an invalid termination of a model; e.g. that an operation could
  not have taken place."
  [msg]
  (Inconsistent. msg))

(defn inconsistent?
  "Is a model inconsistent?"
  [model]
  (instance? Inconsistent model))

(defrecord NoOp []
  Model
  (step [m op] m))

(def noop
  "A model which always returns itself, unchanged."
  (NoOp.))

(defrecord Register [value]
  Model
  (step [r op]
    (condp = (:f op)
      :write (Register. (:value op))
      :read  (if (or (nil? (:value op))     ; We don't know what the read was
                     (= value (:value op))) ; Read was a specific value
               r
               (inconsistent
                 (str (pr-str value) "~" (pr-str (:value op)))))
      :query  (if (or (nil? (:value op))     ; We don't know what the read was
                     (= value (:value op))) ; Read was a specific value
               r
               (inconsistent
                 (str (pr-str value) "~" (pr-str (:value op)))))))


  Object
  (toString [r] (pr-str value)))

(defn register
  "A read-write register."
  ([] (Register. nil))
  ([x] (Register. x)))

(defrecord Register [value]
  Model
  (step [r op]
    (condp = (:f op)
      :write (Register. (:value op))
      :query  (if (or (nil? (:value op))
                     (= value (:value op)))
               r
               (inconsistent (str "can't query " (:value op)
                                  " from register " value)))
      :read  (if (or (nil? (:value op))
                     (= value (:value op)))
               r
               (inconsistent (str "can't read " (:value op)
                                  " from register " value)))))
  Object
  (toString [this] (pr-str value)))

(defn a-register
  "A register"
  ([]      (Register. nil))
  ([value] (Register. value)))


(defn db
  "Xenon host for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "installing xenon" version)
      (c/exec :mkdir :-p dir)
      (c/cd dir
       (c/su
        (let [url (str "https://www.dropbox.com/s/51894h03ayt6xtq/xenon-host-" version
                  "-jar-with-dependencies.jar")
             dest (str dir "/xenon-host-" version "-jar-with-dependencies.jar")]
        (c/exec :wget (str url "-O" dest)))
        (cu/start-daemon!
          {:logfile logfile
           :pidfile pidfile
           :chdir   dir}
                binary
                :-cp (str "./xenon-host-" version "-jar-with-dependencies.jar")
                :com.vmware.xenon.host.DecentralizedControlPlaneHost
                (str "--id="  (name node))
                (str "--port=" 8000)
                (str "--bindAddress=" (net/ip (name node)))
                (str "--publicUri=http://" (name node) ":8000")
                (str "--sandbox=" (str dir "/sandbox/xenon"))
                (str "--peerNodes=" (initial-cluster test)))

          (jepsen/synchronize test)
          (Thread/sleep 10000))))

    (teardown! [_ test node]
      (info node "tearing down xenon")
      (cu/stop-daemon! binary pidfile)
      (c/su
        (c/exec :rm :-rf (str dir "/sandbox/xenon" )))
    )

    db/LogFiles
    (log-files [_ test node]
      [logfile])))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn q   [_ _] {:type :invoke, :f :query, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})

(defn client
  "A client for a single compare-and-set register"
  [conn node]
  (reify client/Client
    (setup! [_ test node]
      (client (v/connect (client-url node)
                         {:timeout 5000}) node))

    (invoke! [this test op]
      (let [ [k v] (:value op)]
       (try+
         (case (:f op)
         ;; TODO: Move read to use x-get after custom register can validate query
         :read  (let [value (parse-long (x-query node k))]
                     (assoc op :type :ok, :value (independent/tuple k value)))
         :query (let [value (parse-long (x-query node k))]
                     (assoc op :type :ok, :value (independent/tuple k value)))
         :write (do (x-put node test k v)
                   (assoc op :type, :ok)))
         (catch java.net.SocketTimeoutException e
            (assoc op
                   :type (if (= :read (:f op)) :fail :info)
                   :error :timeout))
         (catch [:status 408] e
            (assoc op
                   :type (if (= :read (:f op)) :fail :info)
                   :error :timeout))
          (catch [:status 409] e
            (assoc op
                   :type (if (= :read (:f op)) :fail :info)
                   :error :conflict))
         (catch [:status 500] e
            (assoc op
                   :type :fail
                   :error :conflict))
         (catch [:status 404] e
            (assoc op :type :fail, :error :not-found)))))

    (teardown! [_ test])))


(defn xenon-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         {:name "xenon"
          :os debian/os
          :db (db "1.4.2-SNAPSHOT")
          :client (client nil nil)
          :nemesis (nemesis/partition-random-halves)
          :model (model/cas-register)
          :generator (->> (independent/concurrent-generator
                            10
                            (range)
                            (fn [k]
                              (->> (gen/mix [r w])
                                   (gen/stagger 1/10)
                                   (gen/limit 100))))
                           (gen/nemesis
                            (gen/seq (cycle [(gen/sleep 5)
                                             {:type :info, :f :start}
                                             (gen/sleep 5)
                                             {:type :info, :f :stop}])))
                          (gen/time-limit (:time-limit opts)))
          :checker (checker/compose
                   {:perf     (checker/perf)
                      :indep (independent/checker
                               (checker/compose
                                 {:timeline (timeline/html)
                                  :linear   checker/linearizable}))})}
         opts))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn xenon-test})
                   (cli/serve-cmd))
            args))