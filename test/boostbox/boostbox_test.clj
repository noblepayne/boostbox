(ns boostbox.boostbox-test
  (:require [clojure.test :refer [use-fixtures deftest testing is]]
            [boostbox.boostbox :as bb]
            [babashka.http-client :as http]
            [jsonista.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as u])
  (:import (java.net ServerSocket)))

;; --- Test Server Setup ---

(def ^:dynamic *test-config*
  "Holds config for the running test server, including the port."
  nil)

(def ^:dynamic *test-logger*
  "Holds logger shutdown hook."
  nil)

(def ^:dynamic *test-server*
  "Holds the running Aleph server instance."
  nil)

(defn get-free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn- setup-test-server
  "Starts the BoostBox server on a random free port for testing."
  []
  (let [port (get-free-port)
        ;; Create a temporary directory for FS storage
        tmp-dir (io/file (System/getProperty "java.io.tmpdir") (str "boostbox-test-" (System/nanoTime)))
        _ (.mkdirs tmp-dir)
        config {:env "DEV"
                :storage "FS"
                :port port
                :base-url (str "http://localhost:" port)
                :allowed-keys #{"test-key"}
                :max-body-size 1024  ;; <-- Set to 1KB for reliable 413 testing
                :root-path (.getAbsolutePath tmp-dir)}
        _ (io/delete-file "logs.test.txt" true)
        logger (u/start-publisher! {:type :simple-file :filename "logs.test.txt"})]

    (alter-var-root #'*test-config* (constantly config))
    (alter-var-root #'*test-logger* (constantly logger))
    (alter-var-root #'*test-server* (constantly (bb/serve config (bb/make-storage config))))

    ;; Clean up the temp directory on shutdown
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (run! io/delete-file (reverse (file-seq tmp-dir))))))))

(defn- teardown-test-server
  "Stops the test server."
  []
  (when-some [server *test-server*]
    (Thread/sleep 250)
    (bb/stop {:srv server :logger *test-logger*})
    (Thread/sleep 250)
    (alter-var-root #'*test-server* (constantly nil))
    (alter-var-root #'*test-config* (constantly nil))))

(defn with-test-server
  "Fixture to start/stop the server for each test namespace."
  [f]
  (setup-test-server)
  (try
    (f)
    (finally
      (teardown-test-server))))

;; Apply the fixture to this namespace
(use-fixtures :once with-test-server)

;; Helper function for a minimal valid boost payload
(defn- minimal-boost-payload []
  {:action "boost"
   :split 1.0
   :value_msat 1000
   :value_msat_total 1000
   :timestamp (str (java.time.Instant/now))
   :message "test boost!"})

;; --- Tests ---

(deftest smoke-test
  (testing "The test harness is wired up."
    (is (= 1 1) "A basic assertion that should always pass.")))

(deftest e2e-boost-lifecycle
  (testing "Full POST-then-GET boost lifecycle"
    (let [base-url (:base-url *test-config*)
          api-key (first (:allowed-keys *test-config*))
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
                (is (= expected decoded-json))))))))))

;; --- Unhappy Path Smoke Tests ---

(deftest smoke-test-413-payload-too-large
  (testing "POSTing a body larger than BB_MAX_BODY returns 413 Payload Too Large"
    (let [base-url (:base-url *test-config*)
          api-key (first (:allowed-keys *test-config*))
          max-size-bytes (:max-body-size *test-config*)
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
      (is (= {"error" "payload too large"} decoded-body)))))

(deftest smoke-test-401-unauthorized
  (testing "POST without a valid x-api-key returns 401 Unauthorized"
    (let [base-url (:base-url *test-config*)
          post-resp (http/post (str base-url "/boost")
                               {:headers {"Content-Type" "application/json"} ; Missing x-api-key
                                :body (json/write-value-as-string (minimal-boost-payload))
                                :throw false})
          decoded-body (json/read-value (:body post-resp))]
      (is (= 401 (:status post-resp)) "Should return 401 Unauthorized")
      (is (= decoded-body {"error" "unauthorized"})))))

(deftest smoke-test-404-not-found
  (testing "GET request for a non-existent boost returns 404 Not Found"
    (let [base-url (:base-url *test-config*)
          ;; A valid-looking ULID that certainly doesn't exist
          non-existent-id (bb/gen-ulid)
          get-resp (http/get (str base-url "/boost/" non-existent-id)
                             {:throw false})
          decoded-body (json/read-value (:body get-resp))]
      (is (= 404 (:status get-resp)) "Should return 404 Not Found")
      (is (= {"error" "unknown boost" "id" non-existent-id}
             decoded-body)))))

(deftest smoke-test-400-invalid-id
  (testing "GET request for an invalid ULID"
    (let [base-url (:base-url *test-config*)
          ;; valid ULID that is not valid by our standards (does not correspond to UUIDv7 exactly)
          non-existent-id "01K9TJFCFENBC87GR7M7CFA8P1"
          get-resp (http/get (str base-url "/boost/" non-existent-id)
                             {:throw false})
          decoded-body (json/read-value (:body get-resp))]
      (is (= 400 (:status get-resp)) "Should return 404 Not Found")
      (is (= {"humanized" {"id" ["must be valid ULID"]}, "in" ["request" "path-params"]}
             decoded-body)))))