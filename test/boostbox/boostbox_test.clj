(ns boostbox.boostbox-test
  (:require [clojure.test :refer [deftest testing is]]
            [boostbox.boostbox :as bb]
            [babashka.http-client :as http]
            [cognitect.aws.client.protocol :as aws-proto]
            [jsonista.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u])
  (:import (java.net ServerSocket)))


;; --- S3 ---
(defn- mock-s3-client
  "A mock client that simulates the cognitect.aws/s3 client behavior. 
   It stores and retrieves data from storage-atom."
  [storage-atom]
  (reify
    aws-proto/Client
    (-get-info [_] (throw (ex-info "not implemented" {})))
    (-stop [_] (throw (ex-info "not implemented" {})))
    (-invoke-async [__ _] (throw (ex-info "not implemented" {})))
    (-invoke [_ {:keys [:op :request]}]
      (case op
        :PutObject
        (let [key (:Key request)
              ;; Read the Body (which is an InputStream/Reader in the real S3 case)
              body (slurp (:Body request))]
          (swap! storage-atom assoc key body)
          {:ETag "mock-etag" :ResponseMetadata {}}) ; Mimic a successful S3 response
        :GetObject
        (let [key (:Key request)]
          (if-let [data (get @storage-atom key)]
            ;; Real S3 returns an InputStream in the body
            {:Body (java.io.ByteArrayInputStream. (.getBytes data "UTF-8"))}
            ;; Real S3 throws an exception, which the S3Storage record must catch
            {:Error {:aws/error-code "NoSuchKey"} :cognitect.anomalies/category :cognitect.anomalies/not-found}))
        (throw (ex-info (str "Unknown S3 operation: " op) {:op op}))))))

;; --- Test Server Setup ---

(defn get-free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn make-test-storage [cfg]
  (case  (:storage cfg)
    "FS" (bb/->LocalStorage (:root-path cfg))
    "S3" (let [{:keys [:access-key :secret-key :region :endpoint :bucket]} cfg
               use_real_s3 (bb/get-env "BB_REAL_S3_IN_TEST" "")
               use_real_s3 (cond
                             (= "true" (str/lower-case use_real_s3)) true
                             (= "1" use_real_s3) true
                             :else false)
               client  (if use_real_s3
                         (bb/s3-client access-key secret-key region endpoint)
                         (mock-s3-client (atom {})))]
           (bb/->S3Storage client bucket))))


(defn- setup-test-server
  "Starts the BoostBox server on a random free port for testing."
  [storage]
  (let [port (get-free-port)
        ;; Create a temporary directory for FS storage
        tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "boostbox-test-" (System/nanoTime)))
        _ (.mkdirs tmp-dir)
        config {:env "DEV"
                :storage storage
                :port port
                :base-url (str "http://localhost:" port)
                :allowed-keys #{"test-key"}
                :max-body-size 1024  ;; <-- Set to 1KB for reliable 413 testing
                :root-path (.getAbsolutePath tmp-dir)
                :access-key "s3accesskey"
                :secret-key "s3secretkey"
                :region "us-east-1"
                :bucket "testbucket"
                :endpoint (bb/parse-s3-endpoint "http://localhost:9000")}
        _ (io/delete-file "logs.test.txt" true)
        logger (u/start-publisher! {:type :simple-file :filename "logs.test.txt"})
        storage-impl (make-test-storage config)
        server (bb/serve config storage-impl)]


    ;; Clean up the temp directory on shutdown
    (when (= storage "FS")
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (run! io/delete-file (reverse (file-seq tmp-dir)))))))
    {:srv server :logger logger :config config :storage storage-impl}))

(defn- teardown-test-server
  "Stops the test server."
  [{:keys [:srv :logger]}]
  (when-some [server srv]
    (Thread/sleep 250)
    (bb/stop {:srv server :logger logger})
    (Thread/sleep 250)))

(defn with-test-server
  "Fixture to start/stop the server for each test namespace."
  [storage]
  (fn
    [f]
    (let [data (setup-test-server storage)
          data (assoc data :test-storage-impl storage)]
      (try
        (f data)
        (finally
          (teardown-test-server data))))))

(defn run-with-storage
  ([f] (run-with-storage ["FS"] f))
  ([storages f]
   (doseq [storage storages
           :let [runner (with-test-server storage)]]
     (runner f))))

;; Helper function for a minimal valid boost payload
(defn- minimal-boost-payload []
  {:action "boost"
   :split 1.0
   :value_msat 1000
   :value_msat_total 1000
   :timestamp (str (java.time.Instant/now))
   :message "test boost!"})

;; --- Tests ---

;; --- Unit ---

(deftest smoke-test
  (testing "The test harness is wired up."
    (is (= 1 1) "A basic assertion that should always pass.")))

(deftest urlencode-handles-spaces
  (testing "URL Encode handles spaces"
    (is (= "%7B%22abc%22%3A%22a%20b%20c%22%7D"
           (bb/encode-header {:abc "a b c"})))))

(deftest test-bolt11-desc
  (testing "basic format"
    (is (str/starts-with? (bb/bolt11-desc "boost" "https://example.com" "msg")
                          "rss::payment::")))

  (testing "empty or nil message"
    (is (= "rss::payment::buy https://example.com"
           (bb/bolt11-desc "buy" "https://example.com" "")))
    (is (= "rss::payment::buy https://example.com"
           (bb/bolt11-desc "buy" "https://example.com" nil))))

  (testing "short message passes through"
    (is (= "rss::payment::buy https://example.com hello world"
           (bb/bolt11-desc "buy" "https://example.com" "hello world"))))

  (testing "never exceeds 639 characters (BOLT11 limit) with realistic inputs"
    ;; Realistic: action ~10 chars, url ~50 chars, leaves ~560 for message
    (let [action "stream"
          url "https://very-long-domain.example.com/path/to/item"
          huge-msg (apply str (repeat 1000 "word "))]
      (is (<= (count (bb/bolt11-desc action url huge-msg)) 639))))

  (testing "no trailing whitespace in any case"
    (is (not (str/ends-with? (bb/bolt11-desc "a" "b" "msg") " ")))
    (is (not (str/ends-with? (bb/bolt11-desc "a" "b" "") " ")))
    (is (not (str/ends-with? (bb/bolt11-desc "a" "b" nil) " "))))

  (testing "truncated messages end with ellipsis"
    (let [action "buy"
          url "https://example.com"
          max-desc-len (- 623 (count action) (count url))
          long-msg (apply str (repeat (+ max-desc-len 10) "x"))
          result (bb/bolt11-desc action url long-msg)]
      (is (str/ends-with? result "...")))))


;; --- e2e ---
(deftest smoke-test-homepage
  (run-with-storage
   (fn [{test-config :config}]
     (testing "GET homepage (/)"
       (let [base-url (:base-url test-config)
             get-resp (http/get base-url
                                {:throw false})]
         (is (= 200 (:status get-resp)) "Should return 200")
         (is (seq (:body get-resp))))))))

(deftest smoke-test-health
  (run-with-storage
   (fn [{test-config :config}]
     (testing "GET healthcheck (/health)"
       (let [base-url (:base-url test-config)
             get-resp (http/get (str base-url "/health")
                                {:throw false})
             decoded-body (json/read-value (:body get-resp))]
         (is (= 200 (:status get-resp)) "Should return 200")
         (is (= {"status" "ok"} decoded-body)))))))

(deftest e2e-boost-lifecycle
  (run-with-storage
   ["FS" "S3"]
   (fn [data]
     (testing (str "Full POST-then-GET boost lifecycle [" (:test-storage-impl data) "]")
       (let [base-url (-> data :config :base-url)
             api-key (-> data  :config :allowed-keys first)
             ;; 1. Define a minimal, valid boost payload
             boost-payload (minimal-boost-payload)
             boost-payload-json (json/write-value-as-string boost-payload)

             ;; 2. POST the new boost
             post-resp (http/post (str base-url "/boost")
                                  {:headers {"x-api-key" api-key
                                             "Content-Type" "application/json"}
                                   :body boost-payload-json
                                   :throw false})

             _ (is (= 201 (:status post-resp)) "POST should return 201 Created")

             post-body (json/read-value (:body post-resp))
             boost-id (get post-body "id")
             boost-url (get post-body "url")]

         (is (string? boost-id) "Response body includes a string ID")
         (is (= (str base-url "/boost/" boost-id) boost-url) "Response URL is correct")

         (testing "GET request for the new boost"
           (let [;; 3. GET the boost by its new ID
                 get-resp (http/get boost-url {:throw false})]

             (is (= 200 (:status get-resp)) "GET request should return 200 OK")

             ;; 4. Verify the x-rss-payment header
             (let [header (get-in get-resp [:headers "x-rss-payment"])]
               (is (some? header) "x-rss-payment header should be present")

               (when header
                 (let [;; The header is URL-encoded JSON
                       decoded-json (-> (java.net.URLDecoder/decode header "UTF-8")
                                        (json/read-value))
                       expected (assoc (json/read-value boost-payload-json) "id" boost-id)]

                   ;; Check we received our sent payload plus id.
                   (is (= expected decoded-json))))))))))))


(deftest test-oscar-fountain-boost
  (run-with-storage
   ["FS" "S3"]
   (fn [data]
     (testing "Oscar's real Fountain boost - extra fields, nulls, and case handling"
       (let [base-url (-> data :config :base-url)
             api-key (-> data :config :allowed-keys first)
             ;; Oscar's actual boost with extras and nulls
             oscar-boost {:action "BOOST"  ; uppercase to test normalization
                          :split 0.05
                          :message "Test Boost 2 👀👀👀👀👀👀"
                          :link "https://fountain.fm/episode/JCIzq3VyFKQVkEzVNA8v?payment=5XIAt66P29Iv6rjSTZUB"
                          :app_name "Fountain"
                          :sender_id "hIWsCYxdBJzlDvu5zpT3"
                          :sender_name "merryoscar@fountain.fm"
                          :sender_npub "npub1unmftuzmkpdjxyj4en8r63cm34uuvjn9hnxqz3nz6fls7l5jzzfqtvd0j2"
                          :recipient_address "ericpp@getalby.com"
                          :value_msat 50000
                          :value_usd 0.049998
                          :value_msat_total 1000000
                          :timestamp "2025-11-07T14:36:23.861Z"
                          :position 5192
                          :feed_guid "917393e3-1b1e-5cef-ace4-edaa54e1f810"
                          :feed_title "Podcasting 2.0"
                          :item_guid "PC20-240"
                          :item_title "Episode 240: Open Source = People!"
                          :publisher_guid nil
                          :publisher_title nil
                          :remote_feed_guid nil
                          :remote_item_guid nil
                          :remote_publisher_guid nil}

             post-resp (http/post (str base-url "/boost")
                                  {:headers {"x-api-key" api-key
                                             "Content-Type" "application/json"}
                                   :body (json/write-value-as-string oscar-boost)
                                   :throw false})]

         (is (= 201 (:status post-resp)) "Should accept Oscar's boost")

         (let [post-body (json/read-value (:body post-resp))
               boost-id (get post-body "id")]
           (when boost-id
             (let [
               boost-url (get post-body "url")
               get-resp (http/get boost-url {:throw false})
               header (get-in get-resp [:headers "x-rss-payment"])
               decoded (-> header
                           (java.net.URLDecoder/decode "UTF-8")
                           (json/read-value))]

           (is (= 200 (:status get-resp)) "GET should return 200")

           ;; Verify normalization
           (is (= "boost" (get decoded "action"))
               "Action should be lowercased from BOOST")

           ;; Verify extra fields pass through (not in schema)
           (is (= "https://fountain.fm/episode/JCIzq3VyFKQVkEzVNA8v?payment=5XIAt66P29Iv6rjSTZUB"
                  (get decoded "link"))
               "Extra field 'link' should be preserved")
           (is (= "npub1unmftuzmkpdjxyj4en8r63cm34uuvjn9hnxqz3nz6fls7l5jzzfqtvd0j2"
                  (get decoded "sender_npub"))
               "Extra field 'sender_npub' should be preserved")

           ;; Verify null handling
           (is (nil? (get decoded "publisher_guid"))
               "Null values should be preserved")
           (is (nil? (get decoded "remote_feed_guid"))
               "Multiple null fields should be preserved")

           ;; Verify HTML renders without errors
           (is (str/includes? (:body get-resp) "Boost Details")
               "HTML view should render successfully")
           (is (str/includes? (:body get-resp) "Test Boost 2")
               "HTML should show the message")))))))))

;; --- Unhappy Path Smoke Tests ---

(deftest smoke-test-413-payload-too-large
  (run-with-storage
   (fn [{test-config :config}]
     (testing "POSTing a body larger than BB_MAX_BODY returns 413 Payload Too Large"
       (let [base-url (:base-url test-config)
             api-key (first (:allowed-keys test-config))
             max-size-bytes (:max-body-size test-config)
             ;; Create a valid payload, but make the 'message' huge.
             payload-template (minimal-boost-payload)
             ;; Generate a message string that, when JSON encoded, will be over max-size-bytes.
             ;; Given max-body-size is 1024, a string of 1100 'A's is guaranteed to be too big.
             large-message (str/join (repeat 1100 "A"))
             large-payload (assoc payload-template :message large-message)
             large-payload-json (json/write-value-as-string large-payload)

             post-resp (http/post (str base-url "/boost")
                                  {:headers {"x-api-key" api-key
                                             "Content-Type" "application/json"}
                                   :body large-payload-json
                                   :throw false})
             decoded-body (json/read-value (:body post-resp))]
         (is (= 413 (:status post-resp))
             (str "Should return 413 Payload Too Large (max size: " max-size-bytes " bytes)"))
         (is (= {"error" "payload too large"} decoded-body)))))))

(deftest smoke-test-401-unauthorized
  (run-with-storage
   (fn [{test-config :config}]
     (testing "POST without a valid x-api-key returns 401 Unauthorized"
       (let [base-url (:base-url test-config)
             post-resp (http/post (str base-url "/boost")
                                  {:headers {"Content-Type" "application/json"} ; Missing x-api-key
                                   :body (json/write-value-as-string (minimal-boost-payload))
                                   :throw false})
             decoded-body (json/read-value (:body post-resp))]
         (is (= 401 (:status post-resp)) "Should return 401 Unauthorized")
         (is (= decoded-body {"error" "unauthorized"})))))))

(deftest smoke-test-404-not-found
  (run-with-storage
   ["FS" "S3"]
   (fn [{test-config :config}]
     (testing "GET request for a non-existent boost returns 404 Not Found"
       (let [base-url (:base-url test-config)
             ;; A valid-looking ULID that certainly doesn't exist
             non-existent-id (bb/gen-ulid)
             get-resp (http/get (str base-url "/boost/" non-existent-id)
                                {:throw false})
             decoded-body (json/read-value (:body get-resp))]
         (is (= 404 (:status get-resp)) "Should return 404 Not Found")
         (is (= {"error" "unknown boost" "id" non-existent-id}
                decoded-body)))))))

(deftest smoke-test-400-invalid-id
  (run-with-storage
   (fn [{test-config :config}]
     (doseq [non-existent-id [;; valid ULID that is not valid by our standards (does not correspond to UUIDv7 exactly)
                              "01K9TJFCFENBC87GR7M7CFA8P1"
                              ;; not even a valid uuid or ulid
                              "abc123"]]
       (testing (str "GET request for an invalid ID " non-existent-id)
         (let [base-url (:base-url test-config)
               get-resp (http/get (str base-url "/boost/" non-existent-id)
                                  {:throw false})
               decoded-body (json/read-value (:body get-resp))]
           (is (= 400 (:status get-resp)) "Should return 400")
           (is (= {"humanized" {"id" ["must be valid ULID"]}, "in" ["request" "path-params"]}
                  decoded-body))))))))


(comment
  (remove-ns 'boostbox.boostbox-test)
  (smoke-test))