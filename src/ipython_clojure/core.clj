(ns ipython-clojure.core
  (require [clojure.data.json :as json]
           [clojure.walk :as walk]
           [zeromq.zmq :as zmq]
           [clojure.tools.nrepl :as repl]
           [clojure.tools.nrepl.server :refer [start-server stop-server default-handler]]
           [cider.nrepl.middleware.complete :refer [wrap-complete]]
           [ipython-clojure.messaging.utils :refer [read-message address]]
           [ipython-clojure.messaging.message-proto :refer :all]
           [ipython-clojure.messaging execute history kernel-info auto-complete])
  (:gen-class :main true))

(defn prep-config [args]
  (-> args first slurp json/read-str walk/keywordize-keys))

(defn configure-shell-handler
  [shell-socket iopub-socket repl-conn]
  (fn [message]
    (reply-to-message message shell-socket iopub-socket repl-conn)))

(defn heart-beat
  [addr]
  (fn []
    (let [context (zmq/context 1)
          socket (doto (zmq/socket context :rep)
                   (zmq/bind addr))]
      (while (not (.. Thread currentThread isInterrupted))
        (let [message (zmq/receive socket)]
          (zmq/send socket message))))))

(defn shell-loop [shell-addr iopub-addr repl-conn]
  (let [context (zmq/context 1)
        shell-socket (doto (zmq/socket context :router)
                       (zmq/bind shell-addr))
        iopub-socket (doto (zmq/socket context :pub)
                       (zmq/bind iopub-addr))
        shell-handler (configure-shell-handler shell-socket iopub-socket repl-conn)]
    (while (not (.. Thread currentThread isInterrupted))
      (let [message (read-message shell-socket)]
        (println "Receieved message on shell socket: " message)
        (shell-handler message)))))

(defn nrepl-handler
  []
  (default-handler wrap-complete))

(defn -main [& args]
  (let [hb-addr (address (prep-config args) :hb_port)
        shell-addr (address (prep-config args) :shell_port)
        iopub-addr (address (prep-config args) :iopub_port)
        nrepl-server (start-server :handler (nrepl-handler))]
    (with-open [conn (repl/connect :port (:port nrepl-server))]
      (println (prep-config args))
      (println (str "Connecting heartbeat to " hb-addr))
      (-> hb-addr heart-beat Thread. .start)
      (println (str "Connecting shell to " shell-addr))
      (println (str "Connecting iopub to " iopub-addr))
      (shell-loop shell-addr iopub-addr conn))
    (stop-server nrepl-server)))


