(ns crux.soak.main
  (:require bidi.ring
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [crux.api :as api]
            [crux.soak.config :as config]
            [hiccup2.core :refer [html]]
            [integrant.core :as ig]
            [integrant.repl :as ir]
            [nomad.config :as n]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :as params]
            [ring.util.response :as resp])
  (:import crux.api.NodeOutOfSyncException
           java.io.Closeable
           java.text.SimpleDateFormat
           (java.time ZonedDateTime Duration Instant LocalDate LocalTime ZoneId)
           (java.time.format DateTimeFormatter)
           java.util.Date
           org.eclipse.jetty.server.Server))

(def london (ZoneId/of "Europe/London"))

(defn get-current-weather [node location valid-time]
  (when-let [etx (api/entity-tx (api/db node valid-time) (keyword (str location "-current")))]
    (merge (api/document node (:crux.db/content-hash etx))
           (select-keys etx [:crux.db/valid-time :crux.tx/tx-time]))))

(defn get-weather-forecast [node location ^Date valid-time]
  (->> (for [tx-time (->> (iterate #(.minus ^ZonedDateTime % (Duration/ofDays 1))
                                   (-> (ZonedDateTime/ofInstant (.toInstant valid-time) london)
                                       (.with (LocalTime/of 1 0))))
                          (take 5)
                          (map #(Date/from (.toInstant %))))]
         ;; TODO if there's no report at any day, it'll return the most recent report
         ;; which might mean two reports from one day :/
         (when-let [db (try
                         (api/db node valid-time tx-time)
                         (catch NodeOutOfSyncException e
                           nil))]
           (when-let [etx (api/entity-tx db (keyword (str location "-forecast")))]
             (merge (api/document node (:crux.db/content-hash etx))
                    (select-keys etx [:crux.db/valid-time :crux.tx/tx-time])))))
       (remove nil?)))

(defn filter-weather-map [{:keys [main wind weather]}]
  (let [filtered-main (-> (set/rename-keys main {:temp :temperature})
                          (select-keys [:temperature :feels_like :pressure :humidity]))]
    (when-not (empty? filtered-main)
      (-> filtered-main
          (assoc :wind_speed (:speed wind))
          (assoc :current_weather (:description (first weather)))
          (update :temperature (comp #(- % 273) bigdec))
          (update :feels_like (comp #(- % 273) bigdec))))))

(defn render-time [^Date time]
  (.format (DateTimeFormatter/ofPattern "dd/MM/yyyy HH:mm")
           (ZonedDateTime/ofInstant (.toInstant time) london)))

(defn render-weather-report [weather-report]
  [:div.weather-reports
   [:div.bitemp-inst
    "vt = "
    (render-time (:crux.db/valid-time weather-report))]
   [:div.bitemp-inst
    "tt = "
    (render-time (:crux.tx/tx-time weather-report))]
   (for [[k v] (filter-weather-map weather-report)]
     [:p.weather-report
      [:b (-> (str (name k) ": ")
              (string/replace #"_" " ")
              string/upper-case)]
      v])])

(defn render-weather-page [{:keys [location-id location-name ^Date valid-time
                                   current-weather weather-forecast]}]
  (str
   (html
    [:head
     [:link {:rel "stylesheet" :href "https://cdnjs.cloudflare.com/ajax/libs/normalize/8.0.1/normalize.css"}]]
    [:body
     [:style "body { display: inline-flex; font-family: Helvetica }
              #location { text-align: center; width: 300px; padding-right: 30px; }
              #current-weather { width: 300px; padding-right: 30px; }
              #forecasts { display: inline-flex; }
              #forecasts .weather-report { padding-right: 10px; }
              #forecasts h3 { margin-top: 0px; }
              .bitemp-inst {font-family: monospace}"]
     [:div#location
      [:h1 location-name]
      (let [zdt (ZonedDateTime/ofInstant (.toInstant valid-time) london)]
        [:form {:action "/weather.html"}
         [:input {:type "hidden" :name "location" :value location-id}]
         [:p [:input {:type "date",
                      :name "date",
                      :value (.format DateTimeFormatter/ISO_LOCAL_DATE zdt)}]]
         [:p [:input {:type "time",
                      :name "time",
                      :value (.format (DateTimeFormatter/ofPattern "HH:mm") zdt)}]]
         [:p [:input {:type "submit" :value "Select Date"}]]])]
     (when current-weather
       [:div#current-weather
        [:h2 "Current Weather"]
        (render-weather-report current-weather)])
     [:div#weather-forecast
      [:h2 "Forecast History"]
      [:div#forecasts
       (->> weather-forecast (map render-weather-report))]]])))

(defn weather-handler [req {:keys [crux-node]}]
  (let [location-id (get-in req [:query-params "location"])
        valid-time (-> (ZonedDateTime/of (or (some-> (get-in req [:query-params "date"])
                                                     (LocalDate/parse))
                                             (LocalDate/now london))
                                         (or (some-> (get-in req [:query-params "time"])
                                                     (LocalTime/parse))
                                             (LocalTime/now london))
                                         london)
                       .toInstant
                       (Date/from))]
    (resp/response
     (render-weather-page {:location-id location-id
                           :location-name (-> (api/entity (api/db crux-node)
                                                          (keyword (str location-id "-current")))
                                              (:location-name))
                           :valid-time valid-time
                           :current-weather (get-current-weather crux-node location-id valid-time)
                           :weather-forecast (get-weather-forecast crux-node location-id valid-time)}))))

(defn render-homepage [location-names]
  (str
   (html
    [:head
     [:link {:rel "stylesheet" :type "text/css" :href "https://cdnjs.cloudflare.com/ajax/libs/normalize/8.0.1/normalize.css"}]]
    [:body
     [:div#homepage
      [:style "#homepage { text-align: center; font-family: Helvetica; }
                h1 { font-size: 3em; }
                h2 { font-size: 2em; }"]
      [:h1 "Crux Weather Service"]
      [:h2 "Locations"]
      [:form {:target "_blank" :action "/weather.html"}
       [:p
        (into [:select {:name "location"}]
              (for [location-name (sort location-names)]
                [:option {:value (-> (string/replace location-name #" " "-")
                                     (string/lower-case))}
                 location-name]))]
       [:p [:input {:type "submit" :value "View Location"}]]]]])))

(defn homepage-handler [req {:keys [crux-node]}]
  (let [location-names (->> (api/q (api/db crux-node) '{:find [l]
                                                        :where [[e :location-name l]]})
                            (map first))]
    (resp/response (render-homepage location-names))))

(defn bidi-handler [{:keys [crux-node]}]
  (let [handle-homepage #(homepage-handler % {:crux-node crux-node})]
    (bidi.ring/make-handler ["" [["/" {"index.html" handle-homepage
                                       "weather.html" #(weather-handler % {:crux-node crux-node})}]
                                 [true (bidi.ring/->Redirect 307 handle-homepage)]]])))

(defmethod ig/init-key :soak/crux-node [_ node-opts]
  (let [node (api/start-node node-opts)]
    (log/info "Loading Weather Data...")
    (api/sync node)
    (log/info "Weather Data Loaded!")
    node))

(defmethod ig/halt-key! :soak/crux-node [_ ^Closeable node]
  (.close node))

(defmethod ig/init-key :soak/jetty-server [_ {:keys [crux-node server-opts]}]
  (log/info "Starting jetty server...")
  (jetty/run-jetty (-> (bidi-handler {:crux-node crux-node})
                       (params/wrap-params))
                   server-opts))

(defmethod ig/halt-key! :soak/jetty-server [_ ^Server server]
  (.stop server))

(defn config []
  {:soak/crux-node (merge {:crux.node/topology ['crux.kafka/topology 'crux.kv.rocksdb/kv-store]}
                          (config/crux-node-config))
   :soak/jetty-server {:crux-node (ig/ref :soak/crux-node)
                       :server-opts {:port 8080, :join? false}}})

(defn -main [& args]
  (n/set-defaults! {:secret-keys {:soak (config/load-secret-key)}})
  (ir/set-prep! config)
  (ir/go))
