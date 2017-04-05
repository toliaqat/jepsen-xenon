(ns jepsen.xenon
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clj-http.client :as httpclient]
            [verschlimmbesserung.core :as v]
            [slingshot.slingshot :refer [try+]]
            [knossos.model :as model]
            [jepsen [checker :as checker] 
                    [cli :as cli]
                    [client :as client]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [core :as jepsen]
                    [tests :as tests]]
            [jepsen.control.net  :as net]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(def dir "/opt/xenon")
(def binary "/usr/bin/java")
(def logfile (str dir "/xenon.log"))
(def pidfile (str dir "/xenon.pid"))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

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

(defn examples
  "The HTTP url clients use to talk to a node."
  [node]
  (str (node-url node 8000) "/core/examples"))


(defn client-write-put
  [node test key value]
  (httpclient/put (str (examples node) "/" key ) {:form-params {:name (str value)} :content-type :json}))

(defn client-write-post
  [node test key value]
  (httpclient/post (str (examples node)) {:form-params {:name (str value) :documentSelfLink (str key )} :debug true :content-type :json}))

(defn client-read
  [node key]
  (:name (json/read-str (:body (httpclient/get (str (examples node) "/" key) {:accept :json})) :key-fn keyword )))


(defn db
  "Xenon host for a particular version."
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "installing xenon" version)
      (c/exec :mkdir :-p dir)
      (c/cd dir
       (c/su
        ;;(let [url (str "https://www.dropbox.com/s/51894h03ayt6xtq/xenon-host-" version
        ;;          "-jar-with-dependencies.jar")
        ;;     dest (str dir "/xenon-host-" version "-jar-with-dependencies.jar")]
        ;; (c/exec :wget (str url "-O" dest)))
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
          ;;(post-node-group-join node test)
          (when (= (str (:name node)) "n1") 
             (client-write-post node test "k" "0"))

          (Thread/sleep 10000))))

    (teardown! [_ test node]
      (info node "tearing down xenon"))
      ;;(cu/stop-daemon! binary pidfile)
      ;;(c/su
      ;;  (c/exec :rm :-rf (str dir "/sandbox/xenon" ))))

    db/LogFiles
    (log-files [_ test node]
      [logfile])))

(def mynode nil)

(defn parse-long
  "Parses a string to a Long. Passes through `nil`."
  [s]
  (when s (Long/parseLong s)))

(defn client
  "A client for a single compare-and-set register"
  [conn node]
  (reify client/Client
    (setup! [_ test node]
      (client (v/connect (client-url node)
                         {:timeout 5000}) node))

    (invoke! [this test op]
         (case (:f op)
         :read (assoc op :type :ok, :value (parse-long (client-read node "k")))
         :write (do (client-write-put node test "k" (:value op))
                   (assoc op :type, :ok))))

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
          :model  (model/cas-register)
          :checker checker/linearizable
          :generator (->> (gen/mix [r w])
                          (gen/stagger 1)
                          (gen/clients)
                          (gen/time-limit 15))}
         opts))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn xenon-test})
                   (cli/serve-cmd))
            args))
