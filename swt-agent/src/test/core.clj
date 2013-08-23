(ns test.core
  (:use [clojure.java io])
  (:import [org.eclipse.swt SWT]
           [org.eclipse.swt.widgets Display Shell Button Text]
           [org.eclipse.swt.layout FillLayout]
           [org.eclipse.swt.events SelectionAdapter]))


(defn gui-loop [display shell]
  (when-not (.isDisposed shell)
    (if-not (.readAndDispatch display)
      (.sleep display))
    (recur display shell)))

(def my-ref (agent 0))
(def my-agent (agent nil))

(defn do-in-background [x]
  (do
    (send-off *agent* do-in-background)
    (send-off my-ref inc)
    (println "after " @my-ref)
    (Thread/sleep 50)))

(defn init-background-task [handler]
  (add-watch my-ref nil 
             (fn [_ _ old-value new-value]
               (println (str old-value "->" new-value))
               (println "Background Thread: " (Thread/currentThread))

               (handler (str old-value "->" new-value))))
  
  (send-off my-agent do-in-background))

(defn gui-main []
  (let [display (Display.)
        shell   (doto (Shell. display)
                  (.open)
                  (.setText "simple application")
                  (.setLayout (FillLayout. SWT/HORIZONTAL)))

        text (doto (Text. shell SWT/SINGLE)
               (.setText "NONE"))

        set-text (fn [str]
                   (.asyncExec display 
                               (proxy [Runnable] []
                                 (run []
                                   (.setText text str)))))

        button (doto (Button. shell SWT/PUSH)
                 (.setText "start")
                 (.addSelectionListener (proxy [SelectionAdapter] []
                                          (widgetSelected [evt]
                                            (init-background-task set-text)))))]
    (doto shell
      (.pack)
      (.open))

    (println "GUI Thread: " (Thread/currentThread))

    (gui-loop display shell)
    (.dispose display)))

(defn -main [& args]
  (gui-main))

