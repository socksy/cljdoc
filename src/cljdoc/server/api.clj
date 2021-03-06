(ns cljdoc.server.api
  (:require [cljdoc.server.yada-jsonista] ; extends yada multimethods
            [cljdoc.analysis.service :as analysis-service]
            [cljdoc.util.telegram :as telegram]
            [cljdoc.server.ingest :as ingest]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.util]
            [cljdoc.util.clojars :as clojars]
            [cljdoc.cache]
            [cljdoc.config] ; should not be necessary but instead be passed as args
            [cljdoc.renderers.html :as html]
            [clojure.tools.logging :as log]
            [aleph.http :as http]
            [yada.yada :as yada]
            [yada.request-body :as req-body]
            [yada.body :as res-body]
            [byte-streams :as bs]
            [jsonista.core :as json]))


;; Circle CI API stuff -----------------------------------------------
;; TODO move into separate namespace and maybe record later

(defn get-build [circle-ci-config build-num]
  @(http/get (str "https://circleci.com/api/v1.1/project/" (:builder-project circle-ci-config) "/" build-num)
             {:accept "application/json"
              :basic-auth [(:api-token circle-ci-config) ""]}))

;; cljdoc API client functions ---------------------------------------

(defn run-full-build [params]
  @(http/post (str "http://localhost:" (get-in (cljdoc.config/config) [:cljdoc/server :port]) "/api/full-build")
              {:form-params params
               :content-type "application/x-www-form-urlencoded"
               :basic-auth ["cljdoc" "cljdoc"]})) ;TODO fix

(defn test-webhook [circle-ci-config build-num]
  (let [payload (-> (get-build circle-ci-config build-num) :body bs/to-string json/read-value)]
    @(http/post (str "http://localhost:" (get-in (cljdoc.config/config) [:cljdoc/server :port]) "/api/hooks/circle-ci")
                {:body (json/write-value-as-string {"payload" payload})
                 :content-type "application/json"})))

;; Auth --------------------------------------------------------------

(defn mk-auth [known-users]
  (fn authenticate [[user password]]
    (if (get known-users user)
      {:user  user
       :roles #{:api-user}}
      {})))

(defn api-acc-control [users]
  {:realm "accounts"
   :scheme "Basic"
   :verify (mk-auth users)
   :authorization {:methods {:post :api-user}}})

(defn circle-ci-webhook-handler [{:keys [analysis-service build-tracker]}]
  (assert (analysis-service/circle-ci? analysis-service))
  (yada/handler
   (yada/resource
    {:methods
     {:post
      {:consumes #{"application/json"}
       :produces "text/plain"
       :response (fn webhook-req-handler [ctx]
                   ;; TODO implement some rate limiting based on build-number
                   ;; this should be sufficient for now since triggering new
                   ;; builds requires authentication
                   (let [build-num (get-in ctx [:body "payload" "build_num"])
                         project   (get-in ctx [:body "payload" "build_parameters" "CLJDOC_PROJECT"])
                         version   (get-in ctx [:body "payload" "build_parameters" "CLJDOC_PROJECT_VERSION"])
                         build-id  (get-in ctx [:body "payload" "build_parameters" "CLJDOC_BUILD_ID"])
                         status    (get-in ctx [:body "payload" "status"])
                         cljdoc-edn (cljdoc.util/cljdoc-edn project version)
                         artifacts  (-> (analysis-service/get-circle-ci-build-artifacts analysis-service build-num)
                                        :body bs/to-string json/read-value)]
                     (if-let [artifact (and (= status "success")
                                            (= 1 (count artifacts))
                                            (= cljdoc-edn (get (first artifacts) "path"))
                                            (first artifacts))]
                       (let [full-build-req (run-full-build {:project project
                                                             :version version
                                                             :build-id build-id
                                                             :cljdoc-edn (get artifact "url")})]
                         (assoc (:response ctx) :status (:status full-build-req)))

                       (do
                         (if (= status "success")
                           (build-log/failed! build-tracker build-id "unexpected-artifacts")
                           (build-log/failed! build-tracker build-id "analysis-job-failed"))
                         (assoc (:response ctx) :status 200)))))}}})))

(defn initiate-build-handler-simple [{:keys [analysis-service build-tracker]}]
  (yada/handler
   (yada/resource
    {:methods
     {:post
      ;; TODO don't take jarpath as an argument here but instead derive it
      ;; from project and version information. If we take jarpath as an argument
      ;; we lose the immutability guarantees that we get when only talking to Clojars
      {:parameters {:form {:project String :version String}}
       :consumes #{"application/x-www-form-urlencoded"}
       :produces "text/html"
       :response (fn initiate-build-handler-simple-response [ctx]
                   (let [project   (symbol (get-in ctx [:parameters :form :project]))
                         version   (get-in ctx [:parameters :form :version])
                         ;; WARN this introduces some coupling to clojars, making it a little
                         ;; less easy to build documentation for artifacts only existing in local ~/.m2
                         a-uris    (clojars/artifact-uris project version)
                         build-id  (build-log/analysis-requested!
                                    build-tracker
                                    (cljdoc.util/group-id project)
                                    (cljdoc.util/artifact-id project)
                                    version)
                         ana-resp  (analysis-service/trigger-build
                                    analysis-service
                                    {:project project
                                     :version version
                                     :jarpath (:jar a-uris)
                                     :pompath (:pom a-uris)
                                     :build-id build-id})
                         response  (assoc (:response ctx)
                                          :status 301
                                          :headers {"Location" (str "/builds/" build-id)})]
                     (if (analysis-service/circle-ci? analysis-service)
                       (let [build-num (-> ana-resp :body bs/to-string json/read-value (get "build_num"))
                             job-url   (str "https://circleci.com/gh/martinklepsch/cljdoc-builder/" build-num)]
                         (telegram/build-requested project version job-url)
                         (when (= 201 (:status ana-resp))
                           (assert build-num "build number missing from CircleCI response")
                           (build-log/analysis-kicked-off! build-tracker build-id job-url)
                           (log/infof "Kicked of analysis job {:build-id %s :circle-url %s}" build-id job-url))
                         response)
                       (do
                         (build-log/analysis-kicked-off! build-tracker build-id nil)
                         response))))}}})))

(defn initiate-build-handler [{:keys [access-control analysis-service]}]
  ;; TODO assert config correctness
  (yada/handler
   (yada/resource
    {:access-control access-control
     :methods
     {:post
      ;; TODO don't take jarpath as an argument here but instead derive it
      ;; from project and version information. If we take jarpath as an argument
      ;; we lose the immutability guarantees that we get when only talking to Clojars
      {:parameters {:form {:project String :version String :jarpath String}}
       :consumes #{"application/x-www-form-urlencoded"}
       :response (fn initiate-build-handler-response [ctx]
                   (let [build-id  (str (java.util.UUID/randomUUID))
                         ci-resp   (analysis-service/trigger-build
                                    analysis-service
                                    (-> (get-in ctx [:parameters :form])
                                        (assoc :build-id build-id)))
                         build-num (-> ci-resp :body bs/to-string json/read-value (get "build_num"))]
                     (when (= 201 (:status ci-resp))
                       (assert build-num "build number missing from CircleCI response")
                       (log/infof "Kicked of analysis job {:build-id %s :circle-url %s}"
                                  build-id
                                  (str "https://circleci.com/gh/martinklepsch/cljdoc-builder/" build-num)))
                     (assoc (:response ctx) :status (:status ci-resp))))}}})))

(defn full-build-handler [{:keys [dir access-control build-tracker]}]
  ;; TODO assert config correctness
  (yada/handler
   (yada/resource
    {:access-control access-control
     :methods
     {:post
      {:parameters {:form {:project String :version String  :build-id Long :cljdoc-edn String}}
       :consumes #{"application/x-www-form-urlencoded"}
       :response (fn full-build-handler-response [ctx]
                   (let [{:keys [project version build-id cljdoc-edn]} (get-in ctx [:parameters :form])
                         cljdoc-edn-contents (clojure.edn/read-string (slurp cljdoc-edn))]

                     (build-log/analysis-received! build-tracker (int build-id) cljdoc-edn)
                     ;; TODO put this back in place
                     ;; (cljdoc.util/assert-match project version cljdoc-edn)
                     (try
                       (let [{:keys [scm-url commit]} (ingest/ingest-cljdoc-edn dir cljdoc-edn-contents)]
                         (build-log/completed! build-tracker (int build-id) scm-url commit))
                       (assoc (:response ctx) :status 200)
                       (catch Throwable e
                         (build-log/failed! build-tracker build-id "exception-during-import")
                         (throw e)))))}}})))

(def ping-handler
  (yada/handler
   (yada/resource
    {:methods
     {:get {:produces "text/plain"
            :response "pong"}}})))

(def not-implemented-handler
  (yada/handler
   (yada/resource
    {:methods
     {:get {:produces "text/plain"
            :response {:status 501}}}})))

;; Routes -------------------------------------------------------------

(defn routes [{:keys [analysis-service build-tracker dir]}]
  [["/ping"            ping-handler]
   ["/hooks/circle-ci" (if (analysis-service/circle-ci? analysis-service)
                         (circle-ci-webhook-handler
                          {:analysis-service analysis-service
                           :build-tracker build-tracker})
                         not-implemented-handler)]
   ["/request-build"   (initiate-build-handler
                        {:analysis-service analysis-service
                         :access-control (api-acc-control {"cljdoc" "cljdoc"})})]
   ["/request-build2"  (initiate-build-handler-simple
                        {:build-tracker build-tracker
                         :analysis-service analysis-service})]
   ["/full-build"      (full-build-handler
                        {:dir dir
                         :build-tracker build-tracker
                         :access-control (api-acc-control {"cljdoc" "cljdoc"})})]])


(comment
  (-> (cljdoc.config/config) :cljdoc/server :dir)

  (handle-cljdoc-edn (-> (cljdoc.config/config) :cljdoc/server :dir)
                     (clojure.edn/read-string (slurp "/var/folders/tt/hdgn6rc92pv68rscfj8jn8nh0000gn/T/cljdoc-yada-yada-1.2.108517641423396546006/cljdoc-edn/yada/yada/1.2.10/cljdoc.edn"))
                     )

  )
