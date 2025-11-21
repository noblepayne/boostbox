(ns boostbox.boostbox
  (:gen-class)
  (:require [clojure.java.io :as io]
            [aleph.http :as httpd]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.endpoint]
            [cognitect.aws.credentials :as aws-creds]
            [dev.onionpancakes.chassis.core :as html]
            [manifold.deferred :as mf]
            [muuntaja.core :as muuntaja]
            [jsonista.core :as json]
            [reitit.ring :as ring]
            [reitit.ring.malli]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja-middleware]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.middleware.exception :as exception]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [reitit.coercion.malli]
            [ring.util.codec :as rcodec]
            [clj-uuid :as uuid]
            [boostbox.ulid :as ulid]
            [com.brunobonacci.mulog :as u]
            [boostbox.images :as images]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.util :as mu]))

;; ~~~~~~~~~~~~~~~~~~~ Setup & Config ~~~~~~~~~~~~~~~~~~~
(defn get-env
  "System/getenv that throws with no default."
  ([key] (let [val (System/getenv key)]
           (if (nil? val)
             (throw (ex-info (str "Missing ENV VAR: " key) {:missing-var key}))
             val)))
  ([key default] (let [val (System/getenv key)]
                   (or val default))))

(defn assert-in-set [allowed val]
  (let [valid? (allowed val)]
    (assert valid? (str "Invalid value: " val ", allowed: " allowed))))

(def S3Endpoint
  [:map
   [:protocol [:enum :http :https]]
   [:hostname [:string {:min 1}]]
   [:port {:optional true} [:int {:min 1 :max 65535}]]])

(defn parse-s3-endpoint [url-string]
  (let [uri (java.net.URI. url-string)
        data {:protocol (keyword (.getScheme uri))
              :hostname (.getHost uri)}
        port (let [p (.getPort uri)] (when (pos? p) p))
        data (if port (assoc data :port port) data)]
    (if (m/validate S3Endpoint data)
      data
      (throw (ex-info "Invalid S3 endpoint configuration"
                      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                       :url url-string
                       :errors (m/explain S3Endpoint data)})))))

(defn config []
  (let [env (get-env "ENV" "PROD")
        _ (assert-in-set #{"DEV" "STAGING" "PROD"} env)
        storage (get-env "BB_STORAGE" "FS")
        _ (assert-in-set #{"FS" "S3"} storage)
        port' (get-env "BB_PORT" "8080")
        port (Integer/parseInt port')
        base-url (get-env "BB_BASE_URL" (str "http://localhost:" port))
        allowed-keys (into #{} (map str/trim (-> (get-env "BB_ALLOWED_KEYS" "v4v4me")
                                                 (str/split #","))))
        _ (assert (seq allowed-keys) "must specify at least one key in BB_ALLOWED_KEYS (comma separated)")
        max-body-size (Long/parseLong (get-env "BB_MAX_BODY" "102400"))
        base-config {:env env :storage storage :port port :base-url base-url
                     :allowed-keys allowed-keys :max-body-size max-body-size}
        storage-config (case storage
                         "FS" {:root-path (get-env "BB_FS_ROOT_PATH" "boosts")}
                         "S3" {:endpoint (parse-s3-endpoint (get-env "BB_S3_ENDPOINT"))
                               :region (get-env "BB_S3_REGION")
                               :access-key (get-env "BB_S3_ACCESS_KEY")
                               :secret-key (get-env "BB_S3_SECRET_KEY")
                               :bucket (get-env "BB_S3_BUCKET")})]
    (into base-config storage-config)))

;; ~~~~~~~~~~~~~~~~~~~ UUID/ULID ~~~~~~~~~~~~~~~~~~~

(defn gen-ulid []
  (let [id (uuid/v7)
        id-bytes (uuid/as-byte-array id)
        ;; n.b. treat as unsigned
        id-int (BigInteger. 1 id-bytes)]
    (ulid/encode id-int 26)))

(defn ulid->uuid [u]
  (-> u ulid/ulid->bytes uuid/as-uuid))

(defn valid-ulid? [u]
  (try
    (let [as-uuid (ulid->uuid u)]
      (= 7 (uuid/get-version as-uuid)))
    (catch Exception _ false)))

;; ~~~~~~~~~~~~~~~~~~~ Storage ~~~~~~~~~~~~~~~~~~~
(defprotocol IStorage
  (store [this id data])
  (retrieve [this id]))

;; ~~~~~~~~~~~~~~~~~~~ FS ~~~~~~~~~~~~~~~~~~~
(defn timestamp->prefix
  "Convert unix timestamp (milliseconds) to \"YYYY/MM/DD\" string.
   
   (timestamp->prefix 1762637504140) => \"2025/11/08\""
  [unix-ms]
  (let [inst (java.time.Instant/ofEpochMilli unix-ms)
        zdt (java.time.ZonedDateTime/ofInstant inst (java.time.ZoneId/of "UTC"))
        year (.getYear zdt)
        month (format "%02d" (.getMonthValue zdt))
        day (format "%02d" (.getDayOfMonth zdt))]
    (str year "/" month "/" day)))

(defrecord LocalStorage [root-path]
  IStorage
  (store [_ id data]
    (let [timestamp (ulid/ulid->timestamp id)
          prefix (timestamp->prefix timestamp)
          output-file (io/file root-path prefix (str id ".json"))
          _ (-> output-file .getParentFile .mkdirs)]
      (json/write-value output-file data)))
  (retrieve [_ id]
    (let [timestamp (ulid/ulid->timestamp id)
          prefix (timestamp->prefix timestamp)
          input-file (io/file root-path prefix (str id ".json"))]
      (try
        (json/read-value input-file)
        (catch java.io.FileNotFoundException e
          (throw (ex-info "File not found during LocalStorage retrieve"
                          {:cognitect.anomalies/category :cognitect.anomalies/not-found :exception e})))))))

;; ~~~~~~~~~~~~~~~~~~~ S3 ~~~~~~~~~~~~~~~~~~~
(defn s3-client
  [access-key secret-key region endpoint-override]
  (aws/client (merge
               (if region
                 {:region region}
                 {:region "us-east-1"})
               (when endpoint-override
                 {:endpoint-override endpoint-override})
               {:api :s3
                :credentials-provider
                (aws-creds/basic-credentials-provider
                 {:access-key-id access-key
                  :secret-access-key secret-key})})))

(defn s3-put [s3c bucket-name key-name content-type content]
  (aws/invoke s3c {:op :PutObject
                   :request {:Bucket bucket-name
                             :Key key-name
                             :Body (.getBytes content "UTF-8")
                             :ContentType content-type}}))
(defn s3-get [s3c bucket-name key-name]
  (aws/invoke s3c {:op :GetObject
                   :request {:Bucket bucket-name
                             :Key key-name}}))

(defrecord S3Storage [client bucket]
  IStorage
  (store [_ id data]
    (let [timestamp (ulid/ulid->timestamp id)
          prefix (timestamp->prefix timestamp)
          output-file (str prefix "/" id ".json")
          response (s3-put client bucket output-file "application/json"
                           (json/write-value-as-string data))]
      (when (contains? response :cognitect.anomalies/category)
        (throw (ex-info "AWS Error during S3Storage store" response)))))
  (retrieve [_ id]
    (let [timestamp (ulid/ulid->timestamp id)
          prefix (timestamp->prefix timestamp)
          input-file (str prefix "/" id ".json")
          get-response (s3-get client bucket input-file)]
      (if (contains? get-response :cognitect.anomalies/category)
        (throw (ex-info "AWS Error during S3Storage retrieve" get-response))
        (json/read-value (:Body get-response))))))

;; ~~~~~~~~~~~~~~~~ IStorage Utils ~~~~~~~~~~~~~~~~

(defn make-storage [cfg]
  (case  (:storage cfg)
    "FS" (LocalStorage. (:root-path cfg))
    "S3" (let [{:keys [:access-key :secret-key :region :endpoint :bucket]} cfg
               client  (s3-client access-key secret-key region endpoint)]
           (S3Storage. client bucket))))

;; ~~~~~~~~~~~~~~~~~~~ Homepage ~~~~~~~~~~~~~~~~~~~
(defn homepage []
  (fn [_]
    {:status 200
     :headers {"content-type" "text/html; charset=utf-8"}
     :body
     (html/html
      [html/doctype-html5
       [:html
        [:head
         [:meta {:charset "utf-8"}]
         [:meta {:name "viewport", :content "width=device-width, initial-scale=1"}]
         [:meta {:name "color-scheme", :content "light dark"}]
         [:title "BoostBox"]
         [:link {:rel "icon" :type "image/png" :href (str "data:image/png;base64," images/favicon)}]
         [:link {:rel "stylesheet" :href "https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.classless.min.css"}]
         [:style "body { text-align: center; }
               main { max-width: 600px; margin: 0 auto; padding: 2rem; }
               h1 { margin-top: 1rem; }
               p { font-size: 1.1rem; color: var(--muted-color); margin: 1.5rem 0; }
               .button-group { display: flex; gap: 1rem; justify-content: center; flex-wrap: wrap; margin-top: 2rem; }
               .button-group a { margin: 0; }"]]
        [:body
         [:main
          [:img {:src (str "data:image/png;base64," images/v4vbox)}]
          [:h1 "BoostBox"]
          [:p "A simple API to store and serve boost metadata"]
          [:div.button-group
           [:a {:href "/docs", :role "button"} "API Documentation"]
           [:a {:href "/openapi.json", :role "button", :target "_blank"} "OpenAPI Spec"]
           [:a {:href "https://github.com/noblepayne/boostbox", :role "button"} "View on GitHub"]]]]]])}))

;; ~~~~~~~~~~~~~~~~~~~ Boost Schemas ~~~~~~~~~~~~~~~~~~~

(defn valid-iso8601? [s]
  (try
    (java.time.Instant/parse s)
    true
    (catch Exception _ false)))

(def BoostMetadata
  [:map
   ;; provided by us
   #_[:id {:optional true} :string]
   ;; provided by boost client
   [:action [:enum :boost :stream]]
   [:split {:json-schema/default 1.0} [:double {:min 0.0}]]
   [:value_msat {:json-schema/default 2222000} [:int {:min 1}]]
   [:value_msat_total {:json-schema/default 2222000} [:int {:min 1}]]
   [:timestamp {:json-schema/default (java.time.Instant/now)}
    [:and :string
     [:fn {:error/message "must be ISO-8601"} valid-iso8601?]]]
   ;; optional  keys
   [:group {:optional true} :string]
   [:message {:optional true :json-schema/default "row of ducks"} :string]
   [:app_name {:optional true} :string]
   [:app_version {:optional true} :string]
   [:sender_id {:optional true} :string]
   [:sender_name {:optional true} :string]
   [:recipient_name {:optional true} :string]
   [:recipient_address {:optional true} :string]
   [:value_usd {:optional true} [:double {:min 0.0}]]
   [:position {:optional true} :int]
   [:feed_guid {:optional true} :string]
   [:feed_title {:optional true} :string]
   [:item_guid {:optional true} :string]
   [:item_title {:optional true} :string]
   [:publisher_guid {:optional true} :string]
   [:publisher_title {:optional true} :string]
   [:remote_feed_guid {:optional true} :string]
   [:remote_item_guid {:optional true} :string]
   [:remote_publisher_guid {:optional true} :string]])

;; ~~~~~~~~~~~~~~~~~~~ GET View ~~~~~~~~~~~~~~~~~~~
(defn encode-header [data]
  (let [json-str (json/write-value-as-string data)
        encoded (rcodec/url-encode json-str)]
    encoded))

(defn format-sats
  "Convert millisatoshis to satoshis with comma formatting"
  [value-msat]
  (when value-msat
    (let [sats (Math/round (double (/ value-msat 1000)))
          formatter (java.text.DecimalFormat. "#,##0")]
      (.format formatter sats))))

(defn boost-metadata-row
  "Renders a single metadata row if value exists"
  [label value]
  (when value
    [:div.boost-field
     [:strong.boost-label label]
     [:span.boost-value value]]))

(defn boost-view
  "Renders RSS payment metadata in a simple HTML page with JSON display."
  [data]
  (let [boost-id (get data "id")
        json-pretty (json/write-value-as-string data (json/object-mapper {:pretty true}))
        sender-name (get data "sender_name")
        value-msats (get data "value_msat")
        sats (format-sats value-msats)
        feed-title (get data "feed_title")
        item-title (get data "item_title")
        app-name (get data "app_name")
        message (get data "message")]
    [html/doctype-html5
     [:html
      [:head
       [:meta {:charset "utf-8"}]
       [:meta {:name "viewport", :content "width=device-width, initial-scale=1"}]
       [:meta {:name "color-scheme", :content "light dark"}]
       [:title (str "BoostBox Boost " boost-id)]
       [:link {:rel "icon" :type "image/png" :href (str "data:image/png;base64," images/favicon)}]
       [:link {:rel "stylesheet" :href "https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.classless.min.css"}]
       [:link {:rel "stylesheet" :href "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.11.1/styles/atom-one-dark.min.css"}]
       [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.11.1/highlight.min.js"}]
       [:style "pre { background: var(--form-element-background-color);
                      border: 1px solid var(--form-element-border-color);
                      padding: 1rem;
                      border-radius: 6px;
                      overflow-x: auto; }
                code { font-size: 0.9rem; }
                .boost-card { border: 1px solid var(--form-element-border-color);
                              padding: 1.5rem;
                              border-radius: 6px;
                              background: var(--card-background-color, transparent); }
                .boost-field { display: grid;
                               grid-template-columns: minmax(100px, max-content) 1fr;
                               gap: 1rem;
                               align-items: start;
                               padding: 0.75rem 0;
                               border-bottom: 1px solid var(--form-element-border-color); }
                .boost-field:last-child { border-bottom: none; }
                .boost-field strong { color: var(--muted-color); }
                .boost-label { font-weight: 600;
                               white-space: nowrap; }
                .boost-value { word-break: break-word; }"]]
      [:body
       [:main
        [:h1 "Boost Viewer"]
        [:section
         [:article.boost-card
          [:h3 "Boost Details"]
          (boost-metadata-row "ID:" boost-id)
          (boost-metadata-row "From:" sender-name)
          (boost-metadata-row "Amount:" (str sats " sats"))
          (boost-metadata-row "Show:" feed-title)
          (boost-metadata-row "Episode:" item-title)
          (boost-metadata-row "App:" app-name)
          (boost-metadata-row "Message:" message)]]
        [:section
         [:h3 "Metadata"]
         [:pre [:code {:class "language-json"} json-pretty]]]]
       [:script "hljs.highlightAll();"]]]]))

(defn get-boost-by-id [cfg storage]
  (fn [{{:keys [:id]} :path-params :as request}]
    (try
      (let [data (.retrieve storage id)
            data-header (encode-header data)
            data-hiccup (boost-view data)]
        {:status 200
         :headers {"access-control-expose-headers" "x-rss-payment"
                   "x-rss-payment" data-header
                   "content-type" "text/html; charset=utf-8"}
         :body (html/html data-hiccup)})
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [:cognitect.anomalies/category]} (ex-data e)]
          (if (= category :cognitect.anomalies/not-found)
            {:status 404 :body {:error "unknown boost" :id id}}
            (throw e)))))))

;; ~~~~~~~~~~~~~~~~~~~ POST View ~~~~~~~~~~~~~~~~~~~

(defn bolt11-desc [action url message]
  (if-let [s (seq message)]
    (let [s-len (count s)
          action-len (count action)
          url-len (count url)
          ;; n.b. bolt11 max is 639, rss:payment:: + two spaces is 16; 639 - 16 = 623
          max-desc-len (max 0 (- 623 url-len action-len))
          ;; truncate to fit in desc
          truncd-s (take max-desc-len s)
          truncd-s-len (count truncd-s)
          ;; when truncated replace last 3 digits to ... to indicate truncation
          new-s (if (<= truncd-s-len 2)
                  ""
                  (if (< truncd-s-len s-len)
                    (concat (take (- truncd-s-len 3) truncd-s) "...")
                    truncd-s))
          new-message (str/join new-s)
          ;; only add space if message is non-empty
          separator (if (seq new-message) " " "")]
      (str "rss::payment::" action " " url separator new-message))
    (str "rss::payment::" action " " url)))

(defn add-boost [cfg storage]
  (fn [{:keys [:body-params] :as request}]
    (let [id (gen-ulid)
          url (str (:base-url cfg) "/boost/" id)
          boost (assoc body-params :id id)
          desc (bolt11-desc (:action boost) url (:message boost))]
      (try
        (.store storage id boost)
        {:status 201
         :body {:id id
                :url url
                :desc desc}}
        (catch Exception e
          (do
            (u/log ::add-boost-exception {:exception e :msg "exception adding boost"})
            {:status 500
             :body {:error "error during boost storage"}}))))))

;; ~~~~~~~~~~~~~~~~~~~ HTTP Server ~~~~~~~~~~~~~~~~~~~
(defn auth-middleware [allowed-keys]
  (fn [handler]
    (fn [request]
      (if (allowed-keys (get-in request [:headers :x-api-key]))
        (handler request)
        {:status 401
         :body {:error :unauthorized}}))))

(defn routes [cfg storage]
  [["/" {:get {:no-doc true :handler (homepage)}}]
   ["/openapi.json" {:get {:no-doc true :handler (swagger/create-swagger-handler)
                           :swagger {:info {:title "BoostBox API"
                                            :description "simple API to store boost metadata"
                                            :version "0.1.0"}
                                     :tags [{:name "boosts" :description "boost api"}
                                            {:name "admin" :description "admin api"}]
                                     :securityDefinitions {"auth" {:type :apiKey
                                                                   :in :header
                                                                   :name "x-api-key"}}}}}]
   ["/health" {:get {:handler (fn [_] {:status 200 :body {:status :ok}})
                     :tags #{"admin"}
                     :summary "healthcheck"
                     :responses {200 {:body [:map [:status [:enum :ok]]]}}}}]
   ["/boost" {:post {:handler (add-boost cfg storage)
                     :tags #{"boosts"}
                     :middleware [(auth-middleware (:allowed-keys cfg))]
                     :summary "Store boost metadata"
                     :swagger {:security [{"auth" []}]}
                     :parameters {:body BoostMetadata}
                     :responses {201 {:body [:map [:id :string] [:url :string] [:desc :string]]}}}}]
   ["/boost/:id" {:get {:handler (get-boost-by-id cfg storage)
                        :tags #{"boosts"}
                        :summary "lookup boost by id"
                        :parameters {:path {:id [:and :string
                                                 [:fn {:error/message "must be valid ULID"} valid-ulid?]]}}
                        :responses {200 {:body :string}
                                    400 {:body [:map [:error :string] [:id :string]]}
                                    401 {:body [:map [:error :string] [:id :string]]}
                                    404 {:body [:map [:error :string] [:id :string]]}}}}]])

(def cors-middleware
  {:name ::cors
   :wrap (fn [handler]
           (fn [request]
             (if (= :options (:request-method request))
               {:status 204
                :headers {"Access-Control-Allow-Origin" "*"
                          "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
                          "Access-Control-Allow-Headers" "Content-Type, X-API-Key"
                          "Access-Control-Max-Age" "3600"}}
               (let [response (handler request)]
                 (assoc-in response [:headers "Access-Control-Allow-Origin"] "*")))))})

(defn default-exception-handler
  "Default safe handler for any exception."
  [^Exception e _]
  {:status 500
   :headers {}
   ::exception e
   :body {:error "internal server error"}})

(defn body-size-limiter-middleware [max-body-size]
  (fn [handler]
    (fn [request]
      (let
       [body-stream (:body request)
        body-bytes (when body-stream (-> body-stream slurp (.getBytes "UTF-8")))
        body-size (if body-stream (alength body-bytes) 0)
        request (if body-stream (assoc request :body (java.io.ByteArrayInputStream. body-bytes)) request)]
        (if (< max-body-size body-size)
          {:status 413 :body {:error "payload too large"}}
          (handler request))))))

(defn http-handler [cfg storage]
  (ring/ring-handler
   (ring/router
    (routes cfg storage)
    {:data {:muuntaja muuntaja/instance
            :coercion (reitit.coercion.malli/create
                       {:error-keys #{:in :humanized}
                        :compile mu/open-schema
                        :default-values true})
            :middleware [swagger/swagger-feature
                         parameters/parameters-middleware
                         muuntaja-middleware/format-negotiate-middleware
                         ;; end of response middleware
                         muuntaja-middleware/format-response-middleware
                         (exception/create-exception-middleware
                          (assoc exception/default-handlers
                                 ;; replace default handler with ours
                                 ::exception/default
                                 default-exception-handler))
                         (body-size-limiter-middleware (:max-body-size cfg))
                         cors-middleware
                         muuntaja-middleware/format-request-middleware
                         coercion/coerce-response-middleware
                         coercion/coerce-request-middleware]}})
   (ring/routes
    (swagger-ui/create-swagger-ui-handler
     {:path "/docs"
      :config {:urls [{:name "openapi" :url "/openapi.json"}]}})
    (ring/create-default-handler))))

(defn exception-wrapper-of-last-resort [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        {:status 500
         :exception e
         :headers {"content-type" "application/json"}
         :body "{\"error\": \"internal server error\"}"}))))

(defn correlation-id-wrapper
  [handler]
  (fn [request]
    (let [existing (get-in request [:headers "x-correlation-id"])
          correlation-id (if existing existing (gen-ulid))
          request (assoc request :correlation-id correlation-id)
          response (handler request)]
      (assoc-in response [:headers "x-correlation-id"] correlation-id))))

(defn mulog-wrapper [handler]
  (fn [{:keys [:request-method :uri :query-params :path-params :body-params :correlation-id] :as request}]
    (u/trace ::http-request
             {:pairs [:correlation-id correlation-id
                      :method request-method
                      :uri uri
                      :query-params query-params
                      :path-params path-params
                      :body-params body-params]
              :capture (fn [{:keys [:status ::exception] :as response}]
                         (let [success (< status 400)
                               base {:status status
                                     :success success}]
                           (if exception
                             (assoc base :exception exception)
                             base)))}
             (handler request))))

(defn vthread-wrapper [handler]
  (fn [request]
    (let [df (mf/deferred)]
      (Thread/startVirtualThread
       (fn []
         (mf/success! df (handler request))))
      df)))

(def runner
  (comp vthread-wrapper
        correlation-id-wrapper
        mulog-wrapper
        exception-wrapper-of-last-resort))

(defn serve
  [cfg storage]
  (let [env (:env cfg)
        dev (= env "DEV")
        handler-factory (fn [] (runner (http-handler cfg storage)))
        handler (if dev (ring/reloading-ring-handler handler-factory) (handler-factory))]
    (httpd/start-server
     handler
     {:port (:port cfg)
      ;; When other than :none our handler is run on a thread pool.
      ;; As we are wrapping our handler in a new virtual thread per request
      ;; on our own, we have no risk of blocking the (aleph) handler thread and
      ;; don't need an additional threadpool onto which to offload.
      :executor :none})))

(defn -main [& _]
  (let [cfg (config)
        storage (make-storage cfg)
        logger (u/start-publisher! {:type :console :pretty? (= (:env cfg) "DEV")})
        srv (serve cfg storage)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (u/log ::app-shutting-down)
                                 (.close srv)
                                 (Thread/sleep 500)
                                 (logger)
                                 (Thread/sleep 500))))
    (u/log ::app-starting-up :app "BoostBox")
    {:srv srv :logger logger :config cfg :storage storage}))

(defn stop [state]
  (.close (:srv state))
  ((:logger state)))

(comment
  (def state (-main))
  (stop state)
  state)
