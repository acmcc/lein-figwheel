(ns
    ^:doc
" Providing functionality to coerce a user supplied configuration into
a configuration that Figwheel can use.


"


    figwheel-sidecar.config
  (:require
   [figwheel-sidecar.utils :as utils]
   [clojure.pprint :as p]
   [clojure.tools.reader.edn :as edn]
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.walk :as walk]
   [cljs.env]))

(defn mkdirs [fpath]
  (let [f (io/file fpath)]
    (when-let [dir (.getParentFile f)] (.mkdirs dir))))

(defn get-build-options [build]
   (or (:build-options build) (:compiler build) {}))

(defn optimizations-none?
  "Given a map of compiler options returns true if a build will be
  compiled in :optimizations :none mode"
  [{:keys [optimizations]}]
  (or (nil? optimizations) (= optimizations :none)))

;; TODO compiler probably handles this now
(defn ensure-output-dirs!
  "Given a build config ensures the existence of the output directories."
  [build]
  (let [{:keys [output-to]} (get-build-options build)]
    (when output-to
      (mkdirs output-to))
    build))

(defn no-seqs
  "Given a walkable Clojure data structure turns the seqs into
  vectors. This is mainly used to help get build configurations across
  the serialization boundary from the lein plugin to the subprocess."
  [b]
  (walk/postwalk #(if (seq? %) (vec %) %) b))

;; fixing up the :figwheel client side options

(defn fix-symbol-vals
  "Given a map found make all symbol values into strings."
  [mappish]
  (zipmap (keys mappish)
          (map #(if (symbol? %) (name %) %) (vals mappish))))

(defn append-build-id
  "Given a build config forward the build :id to the figwheel client config as a :build-id"
  [figwheel build-id]
  (if build-id
    (assoc figwheel :build-id build-id)
    figwheel))

(defn forward-devcard-option
  "Given a build-config has a [:figwheel :devcards] config it make
  sure that the :build-options has :devcards set to true"
  [{:keys [figwheel] :as build}]
  (if (and figwheel (:devcards figwheel))
    (assoc-in build [:build-options :devcards] true)
    build))

(defn prep-build-for-figwheel-client
  "Given a build config that has a :figwheel config in it "
  [{:keys [id figwheel] :as build}]
  (if figwheel
    (assoc build :figwheel
           (-> (if (map? figwheel) figwheel {})
               (append-build-id id)
               fix-symbol-vals))
    build))

(defn figwheel-build? [{:keys [build-options] :as build}]
  (and (optimizations-none? build-options)
       (:figwheel build)))

(defn map-to-vec-builds
  "Cljsbuild allows a builds to be specified as maps. We acommodate that with this function
   to normalize the map back to the standard vector specification. The key is placed into the
   build under the :id key."
  [builds]
  (if (map? builds)
    (vec (map (fn [[k v]] (assoc v :id (name k))) builds))
    builds))

(defn narrow-builds* 
  "Filters builds to the chosen build-ids or if no build-ids specified returns the first
   build with optimizations set to none."
  [builds build-ids]
  (let [builds (map-to-vec-builds builds)
        ;; ensure string ids
        builds (map #(update-in % [:id] name) builds)]
    (vec
     (keep identity
           (if-not (empty? build-ids)
             (keep (fn [bid] (first (filter #(= bid (:id %)) builds))) build-ids)
             [(first (filter optimizations-none? builds))])))))

;; we are only going to work on one build
;; still need to narrow this to optimizations none
(defn narrow-builds
  "Filters builds to the chosen build-id or if no build id specified returns the first
   build with optimizations set to none."
  [project build-ids]
  (update-in project [:cljsbuild :builds] narrow-builds* build-ids))

(defn check-for-valid-options
  "Check for various configuration anomalies."
  [{:keys [http-server-root] :as opts} print-warning build']
  (let [build-options (get-build-options build')
        opts? (and (not (nil? build-options)) (optimizations-none? build'))]
    (map
     #(str "Figwheel Config Error (in project.clj) - " %)
     (filter identity
             (list
              (when-not opts?
                "you have build :optimizations set to something other than :none")
              (when-not (:output-dir build-options)
                "you have not configured an :output-dir in your build"))))))

(defn check-config [figwheel-options builds & {:keys [print-warning]}]
  (if (empty? builds)
    (list
     (str "Figwheel: "
          "No cljsbuild specified. You may have mistyped the build "
          "id on the command line or failed to specify a build in "
          "the :cljsbuild section of your project.clj. You need to have "
          "at least one build with :optimizations set to :none."))
    (mapcat (partial check-for-valid-options figwheel-options print-warning)
            builds)))

(defn normalize-dir
  "If directory ends with '/' then truncate the trailing forward slash."
  [dir]
  (if (and dir (< 1 (count dir)) (re-matches #".*\/$" dir)) 
    (subs dir 0 (dec (count dir)))
    dir))

(defn apply-to-key
  "applies a function to a key, if key is defined."
  [f k opts]
  (if (k opts) (update-in opts [k] f) opts))

(defn namify-module-entries [module-map]
  (if (map? module-map)
    (into {} (map (fn [[k v]]
                    (if (and (map? v)
                             (get v :entries))
                      [k (update-in v [:entries] #(set (map name %)))]
                      [k v]))
                  module-map))
    module-map))

;; TODO this is a hack! need to check all the places that I'm checking for
;; :optimizations :none and check for nil? or :none
(defn default-optimizations-to-none [build-options]
  (if (optimizations-none? build-options)
    (assoc build-options :optimizations :none)
    build-options))

(defn sane-output-to-dir [{:keys [output-to output-dir] :as options}]
  (letfn [(parent [fname] (if-let [p (.getParent (io/file fname))] (str p "/") ""))]
    (if (and #_(opt-none? options)
             (or (nil? output-dir) (nil? output-to)))
      (if (and (nil? output-dir) (nil? output-to))
        (assoc options :output-to "main.js" :output-dir "out")
        (if output-dir ;; probably shouldn't do this
          (assoc options :output-to (str (parent output-dir) "main.js"))
          (assoc options :output-dir (str (parent output-to) "out"))))
      options)))

(comment
  (default-optimizations-to-none {:optimizations :simple})
  
  (sane-output-to-dir {:output-dir "yes" })

  (sane-output-to-dir {:output-to "yes.js"})

  (sane-output-to-dir {:output-dir "yes/there"})

  (sane-output-to-dir {:output-to "outer/yes.js"})
  )

(defn fix-build-options [build-options]
  (->> build-options
    (default-optimizations-to-none)
    (apply-to-key normalize-dir :output-dir)
    (sane-output-to-dir)
    (apply-to-key namify-module-entries :modules)
    (apply-to-key name :main)))

(defn move-compiler-to-build-options [build]
  (-> build
      (assoc :build-options (get-build-options build))
      (dissoc :compiler)))

(defn ensure-id
  "Converts given build :id to a string and if no :id exists generate and id."
  [opts]
  (assoc opts
         :id (name (or
                    (:id opts)
                    (gensym "build_needs_id_")))))

(defn prep-build [build]
  (-> build
      (dissoc :warning-handlers)
      ensure-id
      move-compiler-to-build-options
      (update-in [:build-options] fix-build-options)
      prep-build-for-figwheel-client
      forward-devcard-option
      ensure-output-dirs!
      no-seqs
      (vary-meta assoc ::prepped true)))

(defn prepped? [build]
  (-> build meta ::prepped))

(defn update-figwheel-connect-options [figwheel-server build]
  (if (figwheel-build? build)
    (let [build (prep-build-for-figwheel-client build)]
      (if-not (get-in build [:figwheel :websocket-url]) ;; prefer 
        (let [host (or (get-in build [:figwheel :websocket-host]) "localhost")]
          (if-not (= :js-client-host host)
            (-> build
              (update-in [:figwheel] dissoc :websocket-host)
              (assoc-in [:figwheel :websocket-url]
                        (str "ws://" host ":" (:server-port figwheel-server) "/figwheel-ws")))
            (update-in build [:figwheel] dissoc :websocket-host)))
        build))
    build))

(comment
  (update-figwheel-connect-options {:port 5555} {:figwheel {:websocket-host "llllll"} :yeah 6})
  (update-figwheel-connect-options {:port 5555} {:figwheel {:websocket-host "llllll" :websocket-url "yep"} :yeah 6})
  (update-figwheel-connect-options {:port 5555} {:figwheel true})
  )

(comment
  (fix-figwheel-symbol-keys {:on-jsload 'asdfasdf :hey 5})
  (prep-build-for-figwheel-client {})
  (prep-build-for-figwheel-client { :figwheel true})
  (prep-build-for-figwheel-client { :id "hey" :figwheel true})
  (prep-build-for-figwheel-client { :id "hey" :figwheel {:on-jsload 'heyhey.there :hey 5}})

  ((comp prep-build-for-figwheel-client forward-devcard-option)
   { :id "hey" :figwheel {:on-jsload 'heyhey.there :hey 5}})
  ((comp prep-build-for-figwheel-client forward-devcard-option)
   { :id "hey" :figwheel {:on-jsload 'heyhey.there :hey 5 :devcards true} :build-options {:fun false}})
 )

(defn prep-builds [builds]
  (-> builds
      map-to-vec-builds
      (->> (mapv prep-build))))

(defn prep-options
  "Normalize various configuration input."
  [opts]
  (->> opts
       no-seqs
       (apply-to-key str :ring-handler)
       (apply-to-key str :http-server-root)       
       (apply-to-key str :open-file-command)
       (apply-to-key str :server-logfile)))

;; high level configuration helpers

(defn read-edn-file [file-name]
  (let [file (io/file file-name)]
    (when-let [body (and (.exists file) (slurp file))]
      (edn/read-string body))))

(defn get-project-config
  "This loads the project map form project.clj without merging profiles."
  []
  (when (.exists (io/file "project.clj"))
    (try
      (into {} (map vec (partition 2 (drop 3 (read-string (slurp "project.clj"))))))
      (catch Exception e
        {}))))

(defn project-builds [project]
  (or (get-in project [:figwheel :builds])
      (get-in project [:cljsbuild :builds])))

(defn needs-lein-project-config? []
  (not (.exists (io/file "figwheel.edn"))))

(defn figwheel-ambient-config
  ([project] (figwheel-ambient-config project nil))
  ([project build-ids]
   (assoc
    (if-not (needs-lein-project-config?)
      (let [fig-opts (read-edn-file "figwheel.edn")]
        {:figwheel-options (dissoc fig-opts :builds)
         :all-builds (:builds fig-opts)})
      {:figwheel-options (dissoc (:figwheel project) :builds)
       :all-builds (project-builds project)})
    :build-ids build-ids)))

(defn prep-figwheel-config [config]
  (let [prepped (-> config
                    (update-in [:all-builds] prep-builds)
                    (update-in [:figwheel-options] prep-options))]
    (assoc prepped
           :build-ids
           (mapv :id
                 (narrow-builds* (:all-builds prepped)
                                 (or (not-empty (:build-ids prepped))
                                     (get-in prepped [:figwheel-options :builds-to-start])))))))

(defn add-compiler-env [{:keys [build-options] :as build}]
  (assoc build
         :compiler-env
         (cljs.env/default-compiler-env build-options)))

(defn get-project-builds []
  (into (array-map)
        (map
         (fn [x]
           [(:id x)
            (add-compiler-env x)])
         (:all-builds (prep-figwheel-config (figwheel-ambient-config (get-project-config)))))))


