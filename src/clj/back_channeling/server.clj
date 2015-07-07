(ns back-channeling.server
  (:require [ring.util.servlet :as servlet]
            [clojure.tools.logging :as log])
  (:import [org.xnio ByteBufferSlicePool]
           [io.undertow Undertow Handlers]
           [io.undertow.servlet Servlets]
           [io.undertow.servlet.api DeploymentInfo]
           [io.undertow.servlet.util ImmediateInstanceFactory]
           [io.undertow.websockets WebSocketConnectionCallback ]
           [io.undertow.websockets.core WebSockets WebSocketCallback AbstractReceiveListener]
           [io.undertow.websockets.jsr WebSocketDeploymentInfo]))

(defonce channels (atom {}))

(defn broadcast-message [path message]
  (doseq [[channel user] (get @channels path)]
    (WebSockets/sendText (pr-str message) channel
                         (proxy [WebSocketCallback] []
                           (complete [channel context])
                           (onError [channel context throwable])))))

(defn multicast-message [path message users]
  (doseq [[channel user] (get @channels path)]
    (when (users user)
      (WebSockets/sendText (pr-str message) channel
                           (proxy [WebSocketCallback] []
                             (complete [channel context])
                             (onError [channel context throwable]))))))

(defn bind-user [path ch user]
  (log/info "bind user" user ch)
  (swap! channels assoc-in [path ch] user))

(defn find-users [path]
  (->> (get @channels path)
       vals
       (keep identity)
       (apply hash-set)))

(defn websocket-callback [path {:keys [on-close on-message]}]
  (proxy [WebSocketConnectionCallback] []
    (onConnect [exchange channel]
      (.. channel
          getReceiveSetter
          (set (proxy [AbstractReceiveListener] []
                 (onFullTextMessage
                   [channel message]
                   (when on-message (on-message channel (.getData message))))
                 (onCloseMessage
                   [message channel]
                   (when on-close (on-close channel message))))))
      (.resumeReceives channel)
      (.addCloseTask channel
                     (proxy [org.xnio.ChannelListener] []
                       (handleEvent [channel]
                         (swap! channels update-in [path] dissoc channel)
                         (when on-close (on-close channel nil)))))
      (swap! channels assoc-in [path channel] nil))))

(defn run-server [ring-handler & {port :port websockets :websockets}]
  (let [ring-servlet (servlet/servlet ring-handler)
        servlet-builder (.. (Servlets/deployment)
                            (setClassLoader (.getContextClassLoader (Thread/currentThread)))
                            (setContextPath "")
                            (setDeploymentName "control-bus")
                            (addServlets
                             (into-array
                              [(.. (Servlets/servlet "Ring handler"
                                                     (class ring-servlet)
                                                     (ImmediateInstanceFactory. ring-servlet))
                                   (addMapping "/*"))])))
        container (Servlets/defaultContainer)
        servlet-manager (.addDeployment container servlet-builder)
        handler (Handlers/path)]
    ;; deploy
    (.deploy servlet-manager)

    (doseq [ws websockets]
      (swap! channels assoc (:path ws) {})
      (.addPrefixPath handler
                      (:path ws)
                      (Handlers/websocket
                       (websocket-callback (:path ws) (dissoc ws :path)))))
    (let [server (.. (Undertow/builder)
                     (addHttpListener port "0.0.0.0")
                     (setHandler (.addPrefixPath handler "/" (.start servlet-manager)))
                     (build))]
      (.start server)
      server)))

