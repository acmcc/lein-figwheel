(ns figwheel-sidecar.repl-api
  (:require
   [clojure.java.io :as io]
   [clojure.repl :refer [doc]]
   [clojure.pprint :as pp]
   [figwheel-sidecar.system :as fs]
   [figwheel-sidecar.config :as config]
   [figwheel-sidecar.config-check.ansi :refer [with-color-when]]
   #_[figwheel-sidecar.build-utils :as butils]
   [com.stuartsierra.component :as component]))

;; giving this var a uniq name anticipating this library to be
;; included with clojure :use, and system could easily clash
(defonce ^:dynamic *repl-api-system* nil)

(defn system-asserts []
  (config/system-asserts)
  #_(butils/assert-clojurescript-version))

(defn start-figwheel!
  "If you aren't connected to an env where fighweel is running already,
  this method will start the figwheel server with the passed in build info."
  ([config-source]
   (when *repl-api-system*
     (alter-var-root #'*repl-api-system* component/stop))
   (alter-var-root #'*repl-api-system* (fn [_] (fs/start-figwheel! config-source)))
   nil)
  ([]
   (if *repl-api-system*
     (do
       (alter-var-root #'*repl-api-system* component/start)
       nil)
     ;; if no system exists try to read in a configuration
     (start-figwheel! (config/fetch-config)))))

(defn stop-figwheel!
  "If a figwheel process is running, this will stop all the Figwheel autobuilders and stop the figwheel Websocket/HTTP server."
  []
  (when *repl-api-system*
    (alter-var-root #'*repl-api-system* component/stop)
    nil))

(defn figwheel-running? []
  (or (get-in *repl-api-system* [:figwheel-system :system-running] false)
      (do
        (println "Figwheel System not initialized.\nPlease start it with figwheel-sidecar.repl-api/start-figwheel!")
        nil)))

(defn app-trans
  ([func ids]
   (when (figwheel-running?)
     (let [system (get-in *repl-api-system* [:figwheel-system :system])]
       (reset! system (func @system ids))
       nil)))
  ([func]
   (when (figwheel-running?)
     (let [system (get-in *repl-api-system* [:figwheel-system :system])]
       (reset! system (func @system))
       nil))))

(defn build-once
  "Compiles the builds with the provided build ids
(or the current default ids) once."
  [& ids]
  (app-trans fs/build-once ids))

(defn clean-builds
  "Deletes the compiled artifacts for the builds with the provided
build ids (or the current default ids)."
  [& ids]
  (app-trans fs/clean-builds ids))

(defn stop-autobuild
  "Stops the currently running autobuild process."
  [& ids]
  (app-trans fs/stop-autobuild ids))

(defn start-autobuild
  "Starts a Figwheel autobuild process for the builds associated with
the provided ids (or the current default ids)."
  [& ids]
  (app-trans fs/start-autobuild ids))

(defn switch-to-build
  "Stops the currently running autobuilder and starts building the
builds with the provided ids."
  [& ids]
  (app-trans fs/switch-to-build ids))

(defn reset-autobuild
  "Stops the currently running autobuilder, cleans the current builds,
and starts building the default builds again."
  []
  (app-trans fs/reset-autobuild))

(defn reload-config
  "Reloads the build config, and resets the autobuild."
  []
  (app-trans fs/reload-config))

(defn print-config
  "Prints out the build configs currently focused or optionally the
  configs of the ids provided."
  [& ids]
  (do
    (fs/print-config
     @(get-in *repl-api-system* [:figwheel-system :system])
     ids)
    nil))

(defn cljs-repl
  "Starts a Figwheel ClojureScript REPL for the provided build id (or
the first default id)."
  ([]
   (cljs-repl nil))
  ([id]
   (when (figwheel-running?)
     (fs/cljs-repl (:figwheel-system *repl-api-system*) id))))

(defn fig-status
  "Display the current status of the running Figwheel system."
  []
  (app-trans fs/fig-status))

(defn remove-system []
  (stop-figwheel!)
  (alter-var-root #'*repl-api-system* (fn [_] nil)))

(defn api-help
  "Print out help for the Figwheel REPL api"
  []
  (doc cljs-repl)
  (doc fig-status)
  (doc start-autobuild)
  (doc stop-autobuild)
  (doc build-once)
  (doc clean-builds)
  (doc switch-to-build)
  (doc reset-autobuild)
  (doc reload-config)
  (doc api-help))

(defn start-figwheel-from-lein [figwheel-internal-config-data]
  (let [config-data (-> figwheel-internal-config-data
                        config/map->FigwheelInternalConfigData
                        (vary-meta assoc :validate-config false))]
    (when-let [system (fs/start-figwheel! config-data)]
      (alter-var-root #'*repl-api-system* (fn [_] system))
      (if (false? (:repl (config/figwheel-options config-data)))
        (loop [] (Thread/sleep 30000) (recur))
        ;; really should get the given initial build id here
        (fs/cljs-repl (:figwheel-system system))))))

;; new start from lein code here

(defn config-source [project-config-source]
  (if (config/figwheel-edn-exists?)
    (config/->figwheel-config-source)
    (config/map->LeinProjectConfigSource project-config-source)))

(defn validate-figwheel-conf [project-config-source options]
  (let [{:keys [file] :as config-data}
        (config/->config-data (config-source project-config-source))]
    #_(pp/pprint config-data)
    (config/interactive-validate config-data options)))

(defn launch-from-lein [narrowed-project build-ids]
  (when-let [config-data (validate-figwheel-conf narrowed-project {})]
    (let [{:keys [data] :as figwheel-internal-data}
          (-> config-data
              config/config-data->figwheel-internal-config-data
              config/prep-builds)
          {:keys [figwheel-options all-builds]} data
          ;; TODO this is really outdated
          errors (config/check-config figwheel-options
                                      (config/narrow-builds*
                                       all-builds
                                       build-ids))
          figwheel-internal-final
          (config/populate-build-ids figwheel-internal-data build-ids)]
      #_(pp/pprint figwheel-internal-final)
      (if (empty? errors)
        (start-figwheel-from-lein figwheel-internal-final)
        (do (mapv println errors) false)))))

(comment
  (def proj (config/->config-data (config/->lein-project-config-source)))
  (def error-proj (assoc-in (:data proj) [:figwheel :server-port]
                            "ASDFASDF"))
  
  (launch-from-lein (:data proj) ["asdf"])

  (launch-from-lein (:data proj) ["example-prod"])
  
  )
