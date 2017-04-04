(ns jepsen.xenon
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [clj-http.client :as httpclient]
            [verschlimmbesserung.core :as v]
            [jepsen [cli :as cli]
                    [client :as client]
                    [control :as c]
                    [db :as db]
                    [tests :as tests]]
            [jepsen.control.net  :as net]
            [jepsen.control.util :as cu]
            [jepsen.os.debian :as debian]))

(def dir "/opt/xenon")
(def binary "/usr/bin/java")
(def logfile (str dir "/xenon.log"))
(def pidfile (str dir "/xenon.pid"))

(defn post-node-group-join
  [session node]
  (let []
    (httpclient/post
                (str "https://" (name (get session :node)) ":8000/core/node-groups/default")
      {:headers {"X-Session-Auth" (get session "Sessionauth")}
       :insecure? true
       :content-type :json
       :form-params {:kind (str "com:vmware:xenon:services:common:NodeGroupService:JoinPeerRequest")
                     :memberGroupReference "http://" (net/ip (name node)) ":8000/core/node-groups/default"
                     :membershipQuorum 1
                     :localNodeOptions [ "PEER" ] }})))

(defn node-url
  "An HTTP url for connecting to a node on a particular port."
  [node port]
  (str "http://" (net/ip (name node)) ":" port))

(defn peer-url
  "The HTTP url for other peers to talk to a node."
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
                (str "--publicUri=http://" (net/ip (name node)) ":8000")
                (str "--sandbox=" (str dir "/sandbox/xenon"))
                (str "--peerNodes=" (initial-cluster test)))

          (Thread/sleep 10000))))

    (teardown! [_ test node]
      (info node "tearing down xenon")
      (cu/stop-daemon! binary pidfile)
      (c/su
        (c/exec :rm :-rf dir)))

    db/LogFiles
    (log-files [_ test node]
      [logfile])))

(defn xenon-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         {:name "xenon"
          :os debian/os
          :db (db "1.4.2-SNAPSHOT")}
         opts))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn xenon-test})
                   (cli/serve-cmd))
            args))
