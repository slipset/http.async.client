;;; Copyright 2010 Hubert Iwaniuk

;;; Licensed under the Apache License, Version 2.0 (the "License");
;;; you may not use this file except in compliance with the License.
;;; You may obtain a copy of the License at

;;; http://www.apache.org/licenses/LICENSE-2.0

;;; Unless required by applicable law or agreed to in writing, software
;;; distributed under the License is distributed on an "AS IS" BASIS,
;;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;;; See the License for the specific language governing permissions and
;;; limitations under the License.
(ns http.async.client-test
  "Testing of http.async.client"
  {:author "Hubert Iwaniuk"}
  (:refer-clojure :exclude [await send])
  (:require [http.async.client :refer :all]
            [http.async.client
             [request :refer :all]
             [util :refer :all]]
            [clojure
             [test :refer :all]
             [stacktrace :refer [print-stack-trace]]
             [string :refer [split]]]
            [clojure.tools.logging :as log]
            [aleph.http :as http]
            [aleph.netty]
            [manifold.stream :as stream]
            [clojure.java.io :refer [input-stream]])
  (:import (java.net URI)
           (org.asynchttpclient AsyncHttpClient)
           (org.eclipse.jetty.server Server Request)
           (org.eclipse.jetty.server.handler AbstractHandler)
           (org.eclipse.jetty.continuation ContinuationSupport)
           (org.eclipse.jetty.util.security Constraint)
           (org.eclipse.jetty.security ConstraintMapping ConstraintSecurityHandler
                                       HashLoginService)
           (org.eclipse.jetty.security.authentication BasicAuthenticator)
           (javax.servlet.http HttpServletRequest HttpServletResponse Cookie)
           (java.io ByteArrayOutputStream
                    Closeable
                    File
                    IOException)
           (java.net ConnectException
                     UnknownHostException)
           (java.util.concurrent TimeoutException)))
(set! *warn-on-reflection* true)

(def ^:dynamic *client* nil)
(def ^:dynamic *http-port* nil)
(def ^:dynamic *http-url* nil)
(def ^:dynamic *ws-url* nil)
(def ^:private ^:dynamic *default-encoding* "UTF-8")


;; test suite setup
(def default-handler
  (proxy [AbstractHandler] []
    (handle [target #^Request req #^HttpServletRequest hReq #^HttpServletResponse hResp]
      (log/debug "target: " target req)
      (do
        (.setHeader hResp "test-header" "test-value")
        (let [hdrs (enumeration-seq (.getHeaderNames hReq))]
          (doseq [k hdrs :when (not (contains? #{"Server"
                                                 "Connection"
                                                 "Content-Length"
                                                 "Host"
                                                 "User-Agent"
                                                 "Content-Type"
                                                 "Cookie"} k))]
            (.setHeader hResp k (.getHeader hReq k))))
        (.setContentType hResp "text/plain;charset=utf-8")
        (.setStatus hResp 200)
                                        ; process params
        (condp = target
          "/body" (.write (.getWriter hResp) "Remember to checkout #clojure@freenode")
          "/body-str" (when-let [line (.readLine (.getReader hReq))]
                        (.write (.getWriter hResp) line))
          "/body-multi" (let [#^String body (slurp (.getReader hReq))]
                          (.write (.getWriter hResp) body))
          "/put" (.setHeader hResp "Method" (.getMethod hReq))
          "/post" (do
                    (.setHeader hResp "Method" (.getMethod hReq))
                    (when-let [line (.readLine (.getReader hReq))]
                      (.write (.getWriter hResp) line)))
          "/delete" (.setHeader hResp "Method" (.getMethod hReq))
          "/head" (.setHeader hResp "Method" (.getMethod hReq))
          "/options" (.setHeader hResp "Method" (.getMethod hReq))
          "/stream" (do
                      (let [cont (ContinuationSupport/getContinuation hReq)
                            writer (.getWriter hResp)
                            prom (promise)]
                        (.suspend cont)
                        (future
                          (Thread/sleep 100)
                          (doto writer
                            (.write "part1")
                            (.flush)))
                        (future
                          (Thread/sleep 200)
                          (doto writer
                            (.write "part2")
                            (.flush))
                          (deliver prom true))
                        (future
                          (if @prom
                            (.complete cont)))))
          "/issue-1" (let [writer (.getWriter hResp)]
                       (doto writer
                         (.write "глава")
                         (.flush)))
          "/proxy-req" (.setHeader hResp "Target" (.. req (getRequestURL) (toString)))
          "/cookie" (do
                      (.addCookie hResp (Cookie. "foo" "bar"))
                      (doseq [c (.getCookies hReq)]
                        (.addCookie hResp c)))
          "/branding" (.setHeader hResp "X-User-Agent" (.getHeader hReq "User-Agent"))
          "/basic-auth" (let [auth (.getHeader hReq "Authorization")]
                          (.setStatus
                           hResp
                           (if (= auth "Basic YmVhc3RpZTpib3lz")
                             200
                             401)))
          "/preemptive-auth" (let [auth (.getHeader hReq "Authorization")]
                               (.setStatus
                                hResp
                                (if (= auth "Basic YmVhc3RpZTpib3lz")
                                  200
                                  401)))
          "/timeout" (Thread/sleep 2000)
          "/empty" (.setHeader hResp "Nothing" "Yep")
          "/multi-query" (.setHeader hResp "query" (.getQueryString hReq))
          "/redirect" (.sendRedirect hResp "here")
          "/here" (.write (.getWriter hResp) "Yugo")
          (doseq [n (enumeration-seq (.getParameterNames hReq))]
            (doseq [v (.getParameterValues hReq n)]
              (.addHeader hResp n v))))
        (when-let [q (.getQueryString hReq)]
          (doseq [p (split q #"\&")]
            (let [[k v] (split p #"=")]
              (.setHeader hResp k v))))
        (.setHandled req true)))))

(defn- start-jetty
  ([handler]
   (start-jetty handler {}))
  ([handler {port :port :as opts :or {port 0}}]
   (let [srv (Server. ^Integer port)
         loginSrv (HashLoginService. "MyRealm" "test-resources/realm.properties")
         constraint (Constraint.)
         mapping (ConstraintMapping.)
         security (ConstraintSecurityHandler.)]

     (.addBean srv loginSrv)
     (doto constraint
       (.setName Constraint/__BASIC_AUTH)
       (.setRoles (into-array #{"user"}))
       (.setAuthenticate true))
     (doto mapping
       (.setConstraint constraint)
       (.setPathSpec "/basic-auth"))
     (doto security
       (.setConstraintMappings (vec #{mapping}) #{"user"})
       (.setAuthenticator (BasicAuthenticator.))
       (.setLoginService loginSrv)
       (.setHandler handler))
     (doto srv
       (.setHandler security)
       (.start))
     srv)))

(def non-websocket-request
  {:status 400
   :headers {"content-type" "application/text"}
   :body "Expected a websocket request."})

(defn ws-echo-handler
  [req]
  (if-let [socket (try
                    @(http/websocket-connection req)
                    (catch Exception e
                      (log/warn e "Can't open websocket for request" req)
                      nil))]
    (do (log/info "Received websocket request, implementing loopback")
        (stream/connect socket socket))
    non-websocket-request))

(defn- once-fixture
  "Configures Logger before test here are executed, and closes AHC after tests are done."
  [f]
  (let [http-server (start-jetty default-handler)
        http-port (.getLocalPort ^org.eclipse.jetty.server.NetworkConnector
                                 (first (.getConnectors ^Server http-server)))
        ws-server (http/start-server ws-echo-handler {:port 0})
        ws-port (aleph.netty/port ws-server)]
    (try (binding [*http-port* http-port
                   *http-url* (format "http://localhost:%d/" http-port)
                   *ws-url* (format "ws://localhost:%d/" ws-port)]
           (f))
         (finally
           (.stop ^Server http-server)
           (.close ^Closeable ws-server)))))

(defn- each-fixture
  [f]
  (binding [*client* (create-client)]
    (try (f)
         (finally
           (.close ^AsyncHttpClient *client*)))))

(use-fixtures :once once-fixture)
(use-fixtures :each each-fixture)

;; testing
(deftest test-status
  (let [status (promise)]
    (execute-request *client*
                     (prepare-request :get *http-url*)
                     :status (fn [_ st]
                               (deliver status st)
                               [st :abort]))
    (are [k v] (= (k @status) v)
      :code 200
      :msg "OK"
      :protocol "HTTP/1.1"
      :major 1
      :minor 1)))

(deftest test-receive-headers
  (let [headers (promise)]
    (execute-request *client*
                     (prepare-request :get *http-url*)
                     :headers (fn [_ hds]
                                (deliver headers hds)
                                [hds :abort]))
    (is (= (:test-header @headers) "test-value"))
    (is (thrown? UnsupportedOperationException (.cons ^clojure.lang.APersistentMap @headers '())))
    (is (thrown? UnsupportedOperationException (assoc ^clojure.lang.APersistentMap @headers :a 1)))
    (is (thrown? UnsupportedOperationException (.without ^clojure.lang.APersistentMap @headers :a)))))

(deftest test-status-and-header-callbacks
  (let [status (promise)
        headers (promise)]
    (execute-request *client*
                     (prepare-request :get *http-url*)
                     :status (fn [_ st]
                               (deliver status st)
                               [st :continue])
                     :headers (fn [_ hds]
                                (deliver headers hds)
                                [hds :abort]))
    (are [k v] (= (k @status) v)
      :code 200
      :msg "OK"
      :protocol "HTTP/1.1"
      :major 1
      :minor 1)
    (is (= (:test-header @headers) "test-value"))))

(deftest test-body-part-callback
  (testing "callecting body callback"
    (let [parts (atom #{})
          resp (execute-request *client*
                                (prepare-request :get (str *http-url* "stream"))
                                :part (fn [response ^ByteArrayOutputStream part]
                                        (let [p (.toString part ^String *default-encoding*)]
                                          (swap! parts conj p)
                                          [p :continue])))]
      (await resp)
      (is (contains? @parts "part1"))
      (is (contains? @parts "part2"))
      (is (= 2 (count @parts)))))
  (testing "counting body parts callback"
    (let [cnt (atom 0)
          resp (execute-request *client*
                                (prepare-request :get (str *http-url* "stream"))
                                :part (fn [_ ^ByteArrayOutputStream p]
                                        (swap! cnt inc)
                                        [p :continue]))]
      (await resp)
      (is (= 2 @cnt)))))

(deftest test-body-completed-callback
  (testing "successful response"
    (let [finished (promise)
          resp (execute-request *client*
                                (prepare-request :get *http-url*)
                                :completed (fn [response]
                                             (deliver finished true)))]
      (await resp)
      (is (true? (realized? finished)))
      (is (true? @finished))))
  (testing "execution time" ; What is this actually testing?
    (let [delta (promise)
          req (prepare-request :get (str *http-url* "body"))
          start (System/nanoTime)
          resp (execute-request *client* req
                                :completed (fn [_]
                                             (deliver delta
                                                      (- (System/nanoTime) start))))]
      (is (pos? @delta))))
  (testing "failed response"
    (let [finished (promise)
          resp (execute-request *client*
                                (prepare-request :get "http://not-existing-host/")
                                :completed (fn [response]
                                             (deliver finished true)))]
      (await resp)
      (is (false? (realized? finished))))))

(deftest test-error-callback
  (let [errored (promise)
        resp (execute-request *client* (prepare-request :get "http://not-existing-host/")
                              :error (fn [_ e]
                                       (deliver errored e)))]
    (await resp)
    (is (true? (realized? errored)))
    (is (true? (instance? UnknownHostException @errored)))))

(deftest test-error-callback-throwing
  (let [resp (execute-request *client* (prepare-request :get "http://not-existing-host/")
                              :error (fn [_ _]
                                       (throw (Exception. "boom!"))))]
    (await resp)
    (is (done? resp))
    (is (failed? resp))
    (is (= "boom!" (.getMessage ^Exception (error resp))))))

(deftest test-send-headers
  (let [resp (GET *client* *http-url* :headers {:a 1 :b 2})
        headers (headers resp)]
    (if (realized? (:error resp))
      (print-stack-trace @(:error resp)))
    (is (not (realized? (:error resp))))
    (is (not (empty? headers)))
    (are [k v] (= (k headers) (str v))
      :a 1
      :b 2)))

(deftest test-body
  (let [resp (GET *client* (str *http-url* "body"))
        headers (headers resp)
        body (body resp)]
    (is (not (nil? body)))
    (is (= "Remember to checkout #clojure@freenode" (string resp)))
    (if (contains? headers :content-length)
      (is (= (count (string resp)) (Integer/parseInt (:content-length headers)))))))

(deftest test-query-params
  (let [resp (GET *client* *http-url* :query {:a 3 :b 4})
        headers (headers resp)]
    (is (not (empty? headers)))
    (are [x y] (= (x headers) (str y))
      :a 3
      :b 4)))

(deftest test-query-params-multiple-values
  (let [resp (GET *client* (str *http-url* "multi-query") :query {:multi [3 4]})
        headers (headers resp)]
    (is (not (empty? headers)))
    (is (= "multi=3&multi=4" (:query headers)))))

;; TODO: uncomment this test once AHC throws exception again on body
;; with GET
;; (deftest test-get-params-not-allowed
;;   (is (thrown?
;;        IllegalArgumentException
;;        (GET *client* *http-url* :body "Boo!"))))

(deftest test-post-no-body
  (let [resp (POST *client* (str *http-url* "post"))
        status (status resp)
        headers (headers resp)]
    (are [x] (not (empty? x))
      status
      headers)
    (is (= 200 (:code status)))
    (is (= "POST" (:method headers)))
    (is (done? (await resp)))
    (is (nil? (string resp)))))

(deftest test-post-params
  (let [resp (POST *client* *http-url* :body {:a 5 :b 6})
        headers (headers resp)]
    (is (not (empty? headers)))
    (are [x y] (= (x headers) (str y))
      :a 5
      :b 6)))

(deftest test-post-string-body
  (let [resp (POST *client* (str *http-url* "body-str") :body "TestBody  Encoded?")
        headers (headers resp)]
    (is (not (empty? headers)))
    (is (= "TestBody  Encoded?" (string resp)))))

(deftest test-post-string-body-content-type-encoded
  (let [resp (POST *client* (str *http-url* "body-str")
                   :headers {:content-type "application/x-www-form-urlencoded"}
                   :body "Encode this & string?")
        headers (headers resp)]
    (is (not (empty? headers)))
    (is (= "Encode+this+%26+string%3F" (string resp)))))

(deftest test-post-map-body
  (let [resp (POST *client* *http-url*
                   :body {:u "user" :p "s3cr3t"})
        headers (headers resp)]
    (is (not (empty? headers)))
    (are [x y] (= x (y headers))
      "user" :u
      "s3cr3t" :p)))

(deftest test-post-input-stream-body
  (let [resp (POST *client* (str *http-url* "body-str")
                   :body (input-stream (.getBytes "TestContent" "UTF-8")))
        headers (headers resp)]
    (is (not (empty? headers)))
    (is (= "TestContent" (string resp)))))

(deftest test-post-file-body
  (let [resp (POST *client* (str *http-url* "body-str")
                   :body (File. "test-resources/test.txt"))]
    (is (false? (empty? (headers resp))))
    (is (= "TestContent" (string resp)))))

(deftest test-post-multipart
  (testing "String multipart part"
    (let [resp (POST *client* (str *http-url* "body-multi")
                     :body [{:type  :string
                             :name  "test-name"
                             :value "test-value"}])]
      (is (false? (empty? (headers resp))))
      (let [#^String s (string resp)]
        (is (true? (.startsWith s "--")))
        (are [v] #(.contains s %)
          "test-name" "test-value"))))
  (testing "File multipart part"
    (let [resp (POST *client* (str *http-url* "body-multi")
                     :body [{:type      :file
                             :name      "test-name"
                             :file      (File. "test-resources/test.txt")
                             :mime-type "text/plain"
                             :charset   "UTF-8"}])]
      (is (false? (empty? (headers resp))))
      (let [#^String s (string resp)]
        (is (true? (.startsWith s "--")))
        (are [v] #(.contains s %)
          "test-name" "TestContent"))))
  (testing "Byte array multipart part"
    (let [resp (POST *client* (str *http-url* "body-multi")
                     :body [{:type      :bytearray
                             :name      "test-name"
                             :file-name "test-file-name"
                             :data       (.getBytes "test-content" "UTF-8")
                             :mime-type  "text/plain"
                             :charset    "UTF-8"}])]
      (is (false? (empty? (headers resp))))
      (let [#^String s (string resp)]
        (is (true? (.startsWith s "--")))
        (are [v] #(.contains s %)
          "test-name" "test-file-name" "test-content"))))
  (testing "Multiple multipart parts"
    (let [resp (POST *client* (str *http-url* "body-multi")
                     :body [{:type  :string
                             :name  "test-str-name"
                             :value "test-str-value"}
                            {:type      :file
                             :name      "test-file-name"
                             :file      (File. "test-resources/test.txt")
                             :mime-type "text/plain"
                             :charset   "UTF-8"}
                            {:type      :bytearray
                             :name      "test-ba-name"
                             :file-name "test-ba-file-name"
                             :data       (.getBytes "test-ba-content" "UTF-8")
                             :mime-type  "text/plain"
                             :charset    "UTF-8"}])]
      (is (false? (empty? (headers resp))))
      (let [#^String s (string resp)]
        (is (true? (.startsWith s "--")))
        (are [v] #(.contains s %)
          "test-str-name" "test-str-value"
          "test-file-name" "TestContent"
          "test-ba-name" "test-ba-file-name" "test-ba-content")))))

(deftest test-put
  (let [resp (PUT *client* (str *http-url* "put") :body "TestContent")
        status (status resp)
        headers (headers resp)]
    (are [x] (not (empty? x))
      status
      headers)
    (is (= 200 (:code status)))
    (is (= "PUT" (:method headers)))
    (is (done? (await resp)))
    (is (nil? (string resp)))))

(deftest test-put-no-body
  (let [resp (PUT *client* (str *http-url* "put"))
        status (status resp)
        headers (headers resp)]
    (are [x] (not (empty? x))
      status
      headers)
    (is (= 200 (:code status)))
    (is (= "PUT" (:method headers)))))

(deftest test-delete
  (let [resp (DELETE *client* (str *http-url* "delete"))
        status (status resp)
        headers (headers resp)]
    (are [x] (not (empty? x))
      status
      headers)
    (is (= 200 (:code status)))
    (is (= "DELETE" (:method headers)))))

(deftest test-head
  (let [resp (HEAD *client* (str *http-url* "head"))
        status (status resp)
        headers (headers resp)]
    (are [x] (not (empty? x))
      status
      headers)
    (is (= 200 (:code status)))
    (is (= "HEAD" (:method headers)))))

(deftest test-options
  (let [resp (OPTIONS *client* (str *http-url* "options"))
        status (status resp)
        headers (headers resp)]
    (are [x] (not (empty? x))
      status
      headers)
    (is (= 200 (:code status)))
    (is (= "OPTIONS" (:method headers)))))

(deftest test-stream
  (let [stream (ref #{})
        resp (request-stream *client* :get (str *http-url* "stream")
                             (fn [_ ^ByteArrayOutputStream baos]
                               (dosync (alter stream conj (.toString baos ^String *default-encoding*)))
                               [baos :continue]))
        status (status resp)]
    (await resp)
    (are [x] (not (empty? x))
      status
      @stream)
    (is (= 200 (:code status)))
    (doseq [s @stream]
      (let [part s]
        (is (contains? #{"part1" "part2"} part))))))

(deftest test-get-stream
  (let [resp (GET *client* (str *http-url* "stream"))]
    (await resp)
    (is (= "part1part2" (string resp)))))

(deftest test-stream-seq
  (testing "Simple stream."
    (let [resp (stream-seq *client* :get (str *http-url* "stream"))
          status (status resp)
          headers (headers resp)
          body (body resp)]
      (are [e p] (= e p)
        200 (:code status)
        "test-value" (:test-header headers)
        2 (count body))
      (doseq [s (string headers body)]
        (is (or (= "part1" s) (= "part2" s))))))
  (testing "Backed by queue contract."
    (let [resp (stream-seq *client* :get (str *http-url* "stream"))
          status (status resp)
          headers (headers resp)]
      (are [e p] (= e p)
        200 (:code status)
        "test-value" (:test-header headers))
      (is (= "part1" (first (string resp))))
      (is (= "part2" (first (string resp)))))))


(deftest issue-1
  (is (= "глава" (string (GET *client* (str *http-url* "issue-1"))))))

(deftest get-via-proxy
  (let [resp (GET *client* (str *http-url* "proxy-req") :proxy {:host "localhost" :port *http-port*})
        headers (headers resp)]
    (is (= (str *http-url* "proxy-req") (:target headers)))))

(deftest proxy-creation
  (testing "host and port missing"
    (is (thrown-with-msg? AssertionError #"Assert failed: host"
                          (prepare-request :get "http://not-important/" :proxy {:meaning :less}))))
  (testing "host missing"
    (is (thrown-with-msg? AssertionError #"Assert failed: host"
                          (prepare-request :get "http://not-important/" :proxy {:port 8080}))))
  (testing "port missing"
    (is (thrown-with-msg? AssertionError #"Assert failed: port"
                          (prepare-request :get "http://not-important/" :proxy {:host "localhost"}))))
  (testing "only host and port"
    (let [r (prepare-request :get "http://not-important/" :proxy {:host "localhost"
                                                                  :port 8080})]
      (is (isa? (class r) org.asynchttpclient.Request))))
  (testing "wrong protocol"
    (is (thrown-with-msg? AssertionError #"Assert failed:.*protocol.*"
                          (prepare-request :get "http://not-important/" :proxy {:protocol :wrong
                                                                                :host "localhost"
                                                                                :port 8080}))))
  (testing "http protocol"
    (let [r (prepare-request :get "http://not-important/" :proxy {:protocol :http
                                                                  :host "localhost"
                                                                  :port 8080})]
      (is (isa? (class r) org.asynchttpclient.Request))))
  (testing "https protocol"
    (let [r (prepare-request :get "http://not-important/" :proxy {:protocol :https
                                                                  :host "localhost"
                                                                  :port 8383})]
      (is (isa? (class r) org.asynchttpclient.Request))))
  (testing "protocol but no host nor port"
    (is (thrown-with-msg? AssertionError #"Assert failed: host"
                          (prepare-request :get "http://not-important/" :proxy {:protocol :http}))))
  (testing "host, port, user but no password"
    (is (thrown-with-msg? AssertionError #"Assert failed:.*password.*"
                          (prepare-request :get "http://not-important/" :proxy {:host "localhost"
                                                                                :port 8080
                                                                                :user "name"}))))
  (testing "host, port, password but no user"
    (is (thrown-with-msg? AssertionError #"Assert failed:.*user.*"
                          (prepare-request :get "http://not-important/" :proxy {:host "localhost"
                                                                                :port 8080
                                                                                :password "..."}))))
  (testing "host, port, user and password"
    (let [r (prepare-request :get "http://not-important/" :proxy {:host "localhost"
                                                                  :port 8080
                                                                  :user "name"
                                                                  :password "..."})]
      (is (isa? (class r) org.asynchttpclient.Request))))
  (testing "protocol, host, port, user and password"
    (let [r (prepare-request :get "http://not-important/" :proxy {:protocol :http
                                                                  :host "localhost"
                                                                  :port 8080
                                                                  :user "name"
                                                                  :password "..."})]
      (is (isa? (class r) org.asynchttpclient.Request)))))

(deftest get-with-cookie
  (let [cv "sample-value"
        resp (GET *client* (str *http-url* "cookie")
                  :cookies #{{:domain *http-url*
                              :name "sample-name"
                              :value cv
                              :path "/cookie"
                              :max-age 10
                              :secure false}})
        headers (headers resp)]
    (is (contains? headers :set-cookie))
    (let [cookies (cookies resp)]
      (is (not (empty? cookies)))
      (doseq [cookie cookies]
        (is (or (= (:name cookie) "sample-name")
                (= (:name cookie) "foo")))
        (is (= (:value cookie)
               (if (= (:name cookie) "sample-name")
                 cv
                 "bar")))))))

(deftest get-with-user-agent-branding
  (let [ua-brand "Branded User Agent/1.0"]
    (with-open [client (create-client :user-agent ua-brand)]
      (let [headers (headers (GET client (str *http-url* "branding")))]
        (is (contains? headers :x-user-agent))
        (is (= (:x-user-agent headers) ua-brand))))))

(deftest connection-limiting
  (with-open [client (create-client :max-conns-per-host 1
                                    :max-conns-total 1)]
    (let [url (str *http-url* "timeout")
          r1 (GET client url)
          r2 (GET client url)]
      (is (not (failed? (await r1))))
      (is (failed? r2))
      (is (instance? IOException (error r2))))))

(deftest redirect-convenience-fns
  (let [resp (GET *client* (str *http-url* "redirect"))]
    (is (true? (redirect? resp)))
    (is (= (str *http-url* "here") (location resp)))))

(deftest following-redirect
  (with-open [client (create-client :follow-redirects true)]
    (let [resp (GET client (str *http-url* "redirect") :query {:token "1234"})
          headers (headers resp)]
      (is (false? (contains? headers :token)))
      (is (= "Yugo" (string resp))))))

(deftest content-type-fn
  (let [resp (GET *client* (str *http-url* "body"))]
    (is (.startsWith ^String (content-type resp) "text/plain"))))

(deftest single-set-cookie
  (let [resp (GET *client* (str *http-url* "cookie"))
        cookie (first (cookies resp))
        header (headers resp)]
    (is (string? (:set-cookie header)))
    (is (= (:name cookie) "foo"))
    (is (= (:value cookie) "bar"))))

(deftest await-string
  (let [resp (GET *client* (str *http-url* "stream"))
        body (string (await resp))]
    (is (= body "part1part2"))))

(deftest no-host
  (let [resp (GET *client* "http://notexisting/")]
    (await resp)
    (is (= (class (error resp)) java.net.UnknownHostException))
    (is (true? (failed? resp)))))

(deftest no-realm-for-digest
  (is (thrown-with-msg? IllegalArgumentException #"For DIGEST authentication realm is required"
                        (GET *client* "http://not-important/"
                             :auth {:type :digest
                                    :user "user"
                                    :password "secret"}))))

(deftest authentication-without-user-or-password
  (is (thrown-with-msg? IllegalArgumentException #"For authentication user is required"
                        (GET *client* "http://not-important/"
                             :auth {:password "secret"})))
  (is (thrown-with-msg? IllegalArgumentException #"For authentication password is required"
                        (GET *client* "http://not-important/"
                             :auth {:user "user"})))
  (is (thrown-with-msg? IllegalArgumentException #"For authentication user and password is required"
                        (GET *client* "http://not-important/"
                             :auth {:type :basic}))))

(deftest basic-authentication
  (is (=
       (:code (status (GET *client* (str *http-url* "basic-auth")
                           :auth {:user "beastie"
                                  :password "boys"})))
       200)))

(deftest preemptive-authentication
  (let [url (str *http-url* "preemptive-auth")
        cred {:user "beastie"
              :password "boys"}]
    (testing "Per request configuration"
      (is (=
           (:code (status (GET *client* url
                               :auth cred)))
           401))
      (is (=
           (:code (status (GET *client* url
                               :auth (assoc cred :preemptive true))))
           200)))
    (testing "Global configuration"
      (with-open [c (create-client :auth (assoc cred :preemptive true))]
        (testing "Global preemptive, no per request"
          (is (= 200
                 (:code (status (GET c url))))))
        (testing "Global preeptive, per request preemptive"
          (is (= 200
                 (:code (status (GET c url :auth (assoc cred :preemptive true)))))))
        (testing "Global preemptive, per request no preemptive"
          (is (= 401
                 (:code (status (GET c url :auth (assoc cred :preemptive false)))))))))))

(deftest canceling-request
  (let [resp (GET *client* *http-url*)]
    (is (false? (cancelled? resp)))
    (is (true? (cancel resp)))
    (await resp)
    (is (true? (cancelled? resp)))
    (is (true? (done? resp)))))

(deftest request-timeout
  (testing "timing out"
    (let [resp (GET *client* (str *http-url* "timeout") :timeout 100)]
      (await resp)
      (is (true? (failed? resp)))
      (if (failed? resp)
        (is (instance? TimeoutException (error resp)))
        (println "headers of response that was supposed to timeout." (headers resp)))))
  (testing "infinite timeout"
    (let [resp (GET *client* (str *http-url* "timeout") :timeout -1)]
      (await resp)
      (is (not (failed? resp)))
      (if (failed? resp)
        (do
          (println "Delivered error:" (realized? (:error resp)))
          (print-stack-trace (error resp))))
      (is (true? (done? resp)))))
  (testing "global timeout"
    (with-open [client (create-client :request-timeout 100)]
      (let [resp (GET client (str *http-url* "timeout"))]
        (await resp)
        (is (true? (failed? resp)))
        (if (failed? resp)
          (is (instance? TimeoutException (error resp)))
          (println "headers of response that was supposed to timeout" (headers resp))))))
  (testing "global timeout overwritten by local infinite"
    (with-open [client (create-client :request-timeout 100)]
      (let [resp (GET client (str *http-url* "timeout") :timeout -1)]
        (await resp)
        (is (false? (failed? resp)))
        (is (done? resp)))))
  (testing "global idle connection in pool timeout"
    (with-open [client (create-client :idle-in-pool-timeout 100)]
      (let [resp (GET client (str *http-url* "timeout"))]
        (await resp)
        (is (false? (failed? resp)))
        (when (failed? resp)
          (println "No response received, while excepting it." (.getMessage ^Throwable (error resp))))))))

(deftest connection-timeout
  ;; timeout connection after 1ms
  (with-open [client (create-client :connection-timeout 1)]
    (let [resp (GET client "http://localhost:8124/")]
      (await resp)
      (is (true? (failed? resp)))
      (is (instance? ConnectException (error resp))))))

(deftest read-timeout
  ;; timeout after 1ms
  (with-open [client (create-client :read-timeout 1)]
    (let [resp (GET client (str *http-url* "timeout"))]
      (await resp)
      (is (true? (failed? resp)))
      (is (instance? TimeoutException (error resp))))))

(deftest test-close-empty-body
  (let [closed (promise)
        client (create-client)
        resp (execute-request client (prepare-request :get (str *http-url* "empty"))
                              :completed (fn [response]
                                           @(:body response)))
        _ (future (Thread/sleep 300) (close client) (deliver closed true))]
    (is (deref closed 1000 false))))

(deftest closing-client
  (let [client (create-client)]
    (await (GET client *http-url*))
    (close client)
    (let [resp (GET client *http-url*)]
      (is (true? (failed? resp)))
      (is (instance? IllegalStateException (error resp))))))

(deftest extract-empty-body
  (let [resp (GET *client* (str *http-url* "empty"))]
    (is (nil? (string resp)))))

(deftest response-url
  (let [resp (GET *client* (str *http-url* "query") :query {:a "1?&" :b "+ ="})]
    (is (contains? #{(str *http-url* "query?a=1%3F%26&b=%2B%20%3D")
                     (str *http-url* "query?b=%2B%20%3D&a=1%3F%26")}
                   (url resp)))))

(deftest request-uri
  (let [uri0 (str *http-url* "query?b=%2B%20%3D&a=1%3F%26")
        uri1 (str *http-url* "query?a=1%3F%26&b=%2B%20%3D")
        resp (GET *client* (str *http-url* "query") :query {:a "1?&" :b "+ ="})]
    (is (contains? #{uri0 uri1} (.toString ^URI (uri resp))))))

(deftest basic-ws
  (let [open-latch (promise)
        close-latch (promise)
        receive-latch (promise)]
    (let [ws (websocket *client* *ws-url*
                        :text (fn [_ m] (log/infof "Received websocket text message: %s" m)
                                (deliver receive-latch m))
                        :open (fn [& args] (log/infof "Websocket open")
                                (deliver open-latch :open))
                        :close (fn [& args] (log/infof "Websocket close")
                                 (deliver close-latch :close))
                        :error (fn [& args] (log/errorf "Websocket error")))]
      (is (= (deref open-latch 1000 :timeout) :open))
      (send ws :text "hello")
      (is (= (deref receive-latch 1000 :timeout) "hello"))
      (.sendCloseFrame ws))
    (is (= (deref close-latch 1000 :timeout) :close))))

(deftest ws-xor-text-or-byte
  (let [ws (try (websocket *client* *ws-url* :text (fn [& _]) :byte (fn [& _]))
                (catch java.lang.AssertionError e :boom))]
    (is (= :boom ws))))

(deftest websocket-config
  (with-open [default-client (create-client)]
    (let [default-config (bean (:config (bean default-client)))
          new-max-frame-size (inc (:webSocketMaxFrameSize default-config))
          new-max-buffer-size (inc (:webSocketMaxBufferSize default-config))
          new-aggregate-websocket-frame-fragments (not (:new-aggregate-websocket-frame-fragments default-config))
          new-enable-compression (not (:enableWebSocketCompression default-config))]
      (testing "that we can create a client with websocket config"
        (with-open [client (create-client :websocket {:max-frame-size new-max-frame-size
                                                      :max-buffer-size new-max-buffer-size
                                                      :aggregate-websocket-frame-fragments new-aggregate-websocket-frame-fragments
                                                      :enable-compression new-enable-compression})]
          (let [config (bean (:config (bean client)))]
            (is (= new-max-frame-size (:webSocketMaxFrameSize config)))
            (is (= new-max-buffer-size (:webSocketMaxBufferSize config)))
            (is (= new-aggregate-websocket-frame-fragments (:aggregateWebSocketFrameFragments config)))
            (is (= new-enable-compression (:enableWebSocketCompression config)))))))))
