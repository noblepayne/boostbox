(ns boostbox.boostbox
  (:gen-class)
  (:require [clojure.java.io :as io]
            [aleph.http :as httpd]
            [babashka.http-client :as http]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.endpoint]
            [cognitect.aws.credentials :as aws-creds]
            [dev.onionpancakes.chassis.core :as html]
            [manifold.deferred :as mf]
            [muuntaja.core :as m]
            [jsonista.core :as json]
            [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.middleware.exception :as exception]
            [reitit.ring.malli]
            [reitit.ring.coercion :as coercion]
            [reitit.coercion.malli]
            [clj-uuid :as uuid]
            [boostbox.ulid :as ulid]
            [com.brunobonacci.mulog :as u])
  (:import java.net.URLEncoder))

;; ~~~~~~~~~~~~~~~~~~~ Setup & Config ~~~~~~~~~~~~~~~~~~~
(defmacro str$
  "Compile-time string interpolation with ~{expr} syntax.
   
   (str$ \"Name: ~{name}, Age: ~{(+ age 1)}\")
   => compiles to: (str \"Name: \" name \", Age: \" (+ age 1))"
  [s]
  (let [pattern #"~\{([^}]*)\}|([^~]+)"
        parts (re-seq pattern s)
        forms (reduce (fn [acc [_ expr text]]
                        (cond-> acc
                          text (conj text)
                          expr (conj (read-string expr))))
                      []
                      parts)]
    (if (empty? forms)
      ""
      `(str ~@forms))))

(defn get-env
  "System/getenv that throws with no default."
  ([key] (let [val (System/getenv key)]
           (if (nil? val)
             (throw (ex-info (str$ "Missing ENV VAR: ~{key}") {:missing-var key}))
             val)))
  ([key default] (let [val (System/getenv key)]
                   (or val default))))

(defn assert-in-set [allowed val]
  (let [valid? (allowed val)]
    (assert valid? (str$ "Invalid value: ~{val}, allowed: ~{allowed}"))))

(defn config []
  (let [env (get-env "ENV" "PROD")
        _ (assert-in-set #{"DEV" "STAGING" "PROD"} env)
        storage (get-env "BB_STORAGE" "FS")
        _ (assert-in-set #{"FS" "S3"} storage)
        base-config {:env env :storage storage}
        storage-config (case storage
                         "FS" {:root-path (get-env "BB_FS_ROOT_PATH" "boosts")}
                         "S3" {:endpoint (get-env "BB_S3_ENDPOINT")
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
    (str$ "~{year}/~{month}/~{day}")))

(defrecord LocalStorage [root-path]
  IStorage
  (store [_ id data]
    (let [timestamp (ulid/ulid->timestamp id)
          prefix (timestamp->prefix timestamp)
          output-file (io/file root-path prefix (str$ "~{id}.json"))
          _ (-> output-file .getParentFile .mkdirs)]
      (json/write-value output-file data)))
  (retrieve [_ id]
    (let [timestamp (ulid/ulid->timestamp id)
          prefix (timestamp->prefix timestamp)
          input-file (io/file root-path prefix (str$ "~{id}.json"))]
      (json/read-value input-file))))

;; ~~~~~~~~~~~~~~~~~~~ S3 ~~~~~~~~~~~~~~~~~~~
(defn s3-client [endpoint region access-key secret-key]
  (aws/client {:api :s3
               :region region
               :endpoint-override {:protocol :https :hostname endpoint}
               :credentials-provider
               (aws-creds/basic-credentials-provider
                {:access-key-id access-key
                 :secret-access-key secret-key})}))
(defrecord S3Storage [client bucket]
  IStorage
  (store [_ id data] nil)
  (retrieve [_ id] nil))

;; ~~~~~~~~~~~~~~~~ IStorage Utils ~~~~~~~~~~~~~~~~
(defn make-storage [cfg]
  (case  (:storage cfg)
    "FS" (LocalStorage. (:root-path cfg))
    "S3" (S3Storage. nil nil)))

;; ~~~~~~~~~~~~~~~~~~~ GET View ~~~~~~~~~~~~~~~~~~~
(defn encode-header [data]
  (let [json-str (json/write-value-as-string data)
        ;; URL encode the entire JSON string
        encoded (java.net.URLEncoder/encode json-str "UTF-8")]
    encoded))
(defn boost-view
  "Renders RSS payment metadata in a simple HTML page with JSON display."
  [data]
  (let [json-pretty (json/write-value-as-string data (json/object-mapper {:pretty true}))
        encoded (encode-header data)]
    [html/doctype-html5
     [:html
      [:head
       [:meta {:charset "utf-8"}]
       [:meta {:name "viewport", :content "width=device-width, initial-scale=1"}]
       [:meta {:name "color-scheme", :content "light dark"}]
       [:title "RSS Payment Metadata"]
       [:link {:rel "stylesheet" :href "https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.classless.min.css"}]
       [:link {:rel "stylesheet" :href "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.11.1/styles/atom-one-dark.min.css"}]
       [:script {:src "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.11.1/highlight.min.js"}]
       [:style "pre { background: var(--form-element-background-color);
                      border: 1px solid var(--form-element-border-color);
                      padding: 1rem;
                      border-radius: 6px;
                      overflow-x: auto; }
                code { font-size: 0.9rem; }"]]
      [:body
       [:main
        [:h1 "RSS Payment Metadata"]
        [:section
         [:h2 "Payment Data"]
         [:pre [:code {:class "language-json"} json-pretty]]]
        [:section
         [:h2 "Encoded Header"]
         [:p "For x-rss-payment HTTP response header:"]
         [:pre [:code {:class "language-json"} encoded]]]]
       [:script "hljs.highlightAll();"]]]]))

(defn get-boost-by-id [cfg storage]
  (fn [{{:keys [:id]} :path-params :as request}]
    (if (not (valid-ulid? id))
      {:status 400
       :body {:error "invalid boost id" :id id}}
      (try
        (let [data (.retrieve storage id)
              data-header (encode-header data)
              data-hiccup (boost-view data)]
          {:status 200
           :headers {"x-rss-payment" data-header
                     "content-type" "text/html; charset=utf-8"}
           :body (html/html data-hiccup)})
        (catch java.io.FileNotFoundException _
          {:status 404 :body {:error "unknown boost" :id id}})))))

;; ~~~~~~~~~~~~~~~~~~~ POST View ~~~~~~~~~~~~~~~~~~~
(defn valid-boost? [{:keys [:action] :as data}]
  (#{"boost" "stream"} action))

(defn add-boost [cfg storage]
  (fn [{:keys [:body-params] :as request}]
    (if-not (valid-boost? body-params)
      {:status 400
       :body {:error "invalid boost" :boost body-params}}
      (let [id (gen-ulid)
            url (str$ "/boost/~{id}")]
        (try
          (.store storage id body-params)
          {:status 200
           :body {:id id
                  :url url}}
          (catch Exception e
            (do
              (u/log ::add-boost-exception {:exception e :msg "exception adding boost"})
              {:status 500
               :body {:error "error during boost storage"}})))))))

;; ~~~~~~~~~~~~~~~~~~~ HTTP Server ~~~~~~~~~~~~~~~~~~~

(defn routes [cfg storage]
  [["/" {:get {:handler (fn [_] {:status 200 :body cfg})}}]
   ["/boost" {:post {:handler (add-boost cfg storage)}}]
   ["/boost/:id" {:get {:handler (get-boost-by-id cfg storage)}}]])

(defn http-handler [cfg storage]
  (ring/ring-handler
   (ring/router
    (routes cfg storage)
    {:data {:muuntaja m/instance
            :coercion reitit.coercion.malli/coercion
            :middleware [parameters/parameters-middleware
                         muuntaja/format-negotiate-middleware
                         muuntaja/format-response-middleware
                         #_exception/exception-middleware
                         muuntaja/format-request-middleware
                         coercion/coerce-response-middleware
                         coercion/coerce-request-middleware]}})
   (ring/create-default-handler)))

(defn make-virtual [f]
  (fn [& args]
    (let [deferred (mf/deferred)]
      (Thread/startVirtualThread
       (fn []
         (try
           (mf/success! deferred (apply f args))
           (catch Exception e (do
                                (u/log :http/exception :msg "unhandled http exception" :exception e)
                                #_(throw e)
                                (mf/error! deferred e))))))
      deferred)))

(defn serve
  [cfg storage]
  (let [env (:env cfg)
        dev (= env "DEV")
        handler-factory (fn [] (make-virtual (http-handler cfg storage)))
        handler (if dev (ring/reloading-ring-handler handler-factory) (handler-factory))]
    (httpd/start-server
     handler
     {:port 8080
      ;; When other than :none our handler is run on a thread pool.
      ;; As we are wrapping our handler in a new virtual thread per request
      ;; on our own, we have no risk of blocking the (aleph) handler thread and
      ;; don't need an additional threadpool onto which to offload.
      :executor :none})))

(defn -main [& _]
  (let [cfg (config)
        storage (make-storage cfg)
        logger (u/start-publisher! {:type :console :pretty? true})
        srv (serve cfg storage)]
    {:srv srv :logger logger :config cfg :storage storage}))

(defn stop [state]
  (.close (:srv state))
  ((:logger state)))

(comment
  (def state (-main))
  (stop state)
  state)
