(ns back-channeling.routing
  (:require [om.core :as om :include-macros true]
            [clojure.browser.net :as net]
            [secretary.core :as sec :include-macros true]
            [goog.events :as events]
            [goog.History]
            [goog.history.EventType :as HistoryEventType]
            [goog.net.EventType :as EventType])
  (:use [cljs.reader :only [read-string]])
  (:import [goog.History]))

(defn- setup-routing [app]
  (sec/set-config! :prefix "#")
  (sec/defroute "/" []
    (om/update! app :page :board))
  (sec/defroute "/board/:board-name/" [board-name]
    (om/update! app :page :board))
  (sec/defroute "/board/:board-name/:thread-id" [board-name thread-id]
    (om/update! app :page :board)
    (om/update! app :target-thread (js/parseInt thread-id)))
  (sec/defroute "/curation/:board-name/:thread-id" [board-name thread-id]
    (om/update! app :page :curation)
    (om/update! app :target-thread (js/parseInt thread-id))))
  
(defn- setup-history [owner]
  (let [history (goog.History.)
        navigation HistoryEventType/NAVIGATE]
    (events/listen history
                   navigation
                   #(-> % .-token sec/dispatch!))
    (.setEnabled history true)))

(defn init [app-state owner]
  (setup-routing app-state)
  (setup-history owner))

