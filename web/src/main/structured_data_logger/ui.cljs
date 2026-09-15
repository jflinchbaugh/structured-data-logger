(ns structured-data-logger.ui
  (:require [helix.core :refer [defnc $ <>]]
            [helix.hooks :as hooks]
            [helix.dom :as d]
            [clojure.string :as str]
            [tick.core :as t]
            [cljs.pprint :as pp]
            [cljs-http.client :as http]
            [cljs.core.async :refer [go <!]]
            [structured-data-logger.core :as core]
            [structured-data-logger.localstorage :as ls]))

(def starter-scripts
  {"Counts by Key"
   "(frequencies (keep #(get-in % [:data :food]) entries))"

   "Numeric Average & StdDev"
   "(let [vals (keep #(get-in % [:data :mileage]) entries)]
  {:count (count vals)
   :average (average vals)
   :stddev (stddev vals)})"

   "Time Between Entries (Seconds)"
   "(let [diffs (intervals entries)]
  {:intervals diffs
   :average-interval-sec (average diffs)})"

   "Recent Window (Last 7 Days)"
   "(filter #(try
             (let [inst (t/instant (:timestamp %))]
               (t/> inst (t/- (t/now) (t/new-duration 7 :days))))
             (catch :default _ false))
           entries)"

   "Bar Chart Data"
   "(let [freqs (frequencies (keep #(get-in % [:data :pills]) entries))]
  {:chart-type :bar
   :data freqs})"})

(defn- parse-val [v]
  (let [trimmed (str/trim (str v))]
    (cond
      (re-matches #"^-?\d+$" trimmed) (js/parseInt trimmed 10)
      (re-matches #"^-?\d+\.\d+$" trimmed) (js/parseFloat trimmed)
      :else trimmed)))

(defnc BarChart [{:keys [data]}]
  (let [entries (seq data)
        max-val (if entries (apply max (map second entries)) 1)]
    (d/div
     {:class "card"}
     (d/h4 {:class "card-title"} "Chart Visualization")
     (for [[lbl val] entries]
       (let [num-val (js/Number val)
             pct (if (pos? max-val) (* 100 (/ num-val max-val)) 0)]
         (d/div
          {:key (str lbl) :class "bar-chart-row"}
          (d/span {:class "bar-chart-label"} (str lbl))
          (d/div
           {:class "bar-chart-bar-container"}
           (d/div {:class "bar-chart-bar"
                   :style {:width (str pct "%")}}))
          (d/span {:class "bar-chart-val"} (str num-val))))))))

(defnc EntryForm [{:keys [on-save on-cancel initial-entry all-entries]}]
  (let [[timestamp set-timestamp]
        (hooks/use-state
         (core/to-local-datetime-input (:timestamp initial-entry)))
        [description set-description]
        (hooks/use-state (or (:description initial-entry) ""))
        [kv-list set-kv-list]
        (hooks/use-state
         (vec (for [[k v] (:data initial-entry)]
                {:key (name k) :val (str v)})))
        [new-key-name set-new-key-name] (hooks/use-state "")
        [new-key-val set-new-key-val] (hooks/use-state "")
        recent-k (core/recent-keys all-entries)
        common-k (core/common-keys all-entries)
        all-k (core/all-known-keys all-entries)
        new-key-vals (if (str/blank? new-key-name)
                       []
                       (core/all-known-values all-entries new-key-name))]

    (d/div
     {:class "card"}
     (d/h3 {:class "card-title"}
           (if initial-entry "Edit Journal Entry" "New Journal Entry"))

     (d/datalist
      {:id "known-keys-datalist"}
      (for [k all-k]
        (d/option {:key (str "opt-k-" k) :value k})))

     (d/datalist
      {:id "new-key-values-datalist"}
      (for [v new-key-vals]
        (d/option {:key (str "opt-v-" v) :value v})))

     (d/div
      {:class "form-group"}
      (d/label "Timestamp")
      (d/input {:type "datetime-local"
                :value timestamp
                :on-change #(set-timestamp (.. % -target -value))}))

     (d/div
      {:class "form-group"}
      (d/label "Description")
      (d/textarea {:rows 3
                   :placeholder "e.g. Morning vitamins, jogged 3 miles"
                   :value description
                   :on-change #(set-description (.. % -target -value))}))

     (d/div
      {:class "form-group"}
      (d/label "Current Key:Value Pairs")
      (if (empty? kv-list)
        (d/p {:style {:color "var(--muted)" :fontSize "0.85rem"}}
             "No extra fields yet.")
        (for [[idx item] (map-indexed vector kv-list)]
          (let [row-vals (if (str/blank? (:key item))
                           []
                           (core/all-known-values all-entries (:key item)))
                dl-id (str "row-vals-dl-" idx)]
            (d/div
             {:key (str idx) :class "kv-pair"}
             (d/input {:value (:key item)
                       :placeholder "Key"
                       :list "known-keys-datalist"
                       :on-change
                       #(let [v (.. % -target -value)]
                          (set-kv-list (assoc-in kv-list [idx :key] v)))})
             (d/input {:value (:val item)
                       :placeholder "Value"
                       :list dl-id
                       :on-change
                       #(let [v (.. % -target -value)]
                          (set-kv-list (assoc-in kv-list [idx :val] v)))})
             (d/datalist
              {:id dl-id}
              (for [v row-vals]
                (d/option {:key (str "rv-" idx "-" v) :value v})))
             (d/button {:class "btn btn-danger btn-small"
                        :on-click
                        #(set-kv-list (vec (concat (subvec kv-list 0 idx)
                                                   (subvec kv-list (inc idx)))))}
                       "✕"))))))

     (d/div
      {:class "form-group" :style {:borderTop "1px dashed var(--border)"
                                   :paddingTop "12px"}}
      (d/label "Add Field")
      (d/div
       {:class "kv-pair"}
       (d/input {:placeholder "Key name (e.g. pills, mileage)"
                 :value new-key-name
                 :list "known-keys-datalist"
                 :on-change #(set-new-key-name (.. % -target -value))})
       (d/input {:placeholder "Value (numeric, text)"
                 :value new-key-val
                 :list "new-key-values-datalist"
                 :on-change #(set-new-key-val (.. % -target -value))})
       (d/button
        {:class "btn btn-secondary"
         :on-click
         #(when (not (str/blank? new-key-name))
            (set-kv-list (conj kv-list {:key (str/trim new-key-name)
                                        :val (str/trim new-key-val)}))
            (set-new-key-name "")
            (set-new-key-val ""))}
        "+ Add"))

      (when (seq recent-k)
        (d/div
         (d/span {:style {:fontSize "0.8rem" :color "var(--muted)"}}
                 "Recently used keys:")
         (d/div
          {:class "chip-row"}
          (for [k (take 6 recent-k)]
            (d/span {:key (str "rec-" k)
                     :class "chip"
                     :on-click #(set-new-key-name (name k))}
                    (name k))))))

      (when (seq common-k)
        (d/div
         {:style {:marginTop "8px"}}
         (d/span {:style {:fontSize "0.8rem" :color "var(--muted)"}}
                 "Frequently used keys:")
         (d/div
          {:class "chip-row"}
          (for [k (take 6 common-k)]
            (d/span {:key (str "com-" k)
                     :class "chip"
                     :on-click #(set-new-key-name (name k))}
                    (name k))))))

      (when (not (str/blank? new-key-name))
        (let [kw (keyword (str/trim new-key-name))
              common-v (core/common-values all-entries kw)]
          (when (seq common-v)
            (d/div
             {:style {:marginTop "8px"}}
             (d/span {:style {:fontSize "0.8rem" :color "var(--muted)"}}
                     (str "Common values for '" (name kw) "':"))
             (d/div
              {:class "chip-row"}
              (for [v (take 6 common-v)]
                (d/span {:key (str "val-" v)
                         :class "chip"
                         :on-click #(set-new-key-val (str v))}
                        (str v)))))))))

     (d/div
      {:style {:display "flex" :gap "8px" :marginTop "16px"}}
      (d/button
       {:class "btn btn-primary"
        :on-click
        #(let [pending-pair (when (not (str/blank? new-key-name))
                              {:key (str/trim new-key-name)
                               :val (str/trim new-key-val)})
               effective-kv (if pending-pair
                              (conj kv-list pending-pair)
                              kv-list)
               data-map (into {}
                              (for [{:keys [key val]} effective-kv
                                    :when (not (str/blank? key))]
                                [(keyword (str/trim key)) (parse-val val)]))
               entry (core/create-entry
                      {:id (:id initial-entry)
                       :timestamp (core/from-local-datetime-input timestamp)
                       :description description
                       :data data-map})]
           (on-save entry))}
       "Save Entry")
      (when on-cancel
        (d/button {:class "btn btn-secondary"
                   :on-click on-cancel}
                  "Cancel"))))))

(defnc DashboardView [{:keys [entries]}]
  (let [saved-code (or (ls/get-item :dashboard-code)
                       (get starter-scripts "Counts by Key"))
        [code set-code] (hooks/use-state saved-code)
        [eval-out set-eval-out] (hooks/use-state nil)
        [chart-data set-chart-data] (hooks/use-state nil)]

    (let [run-code
          (fn [src]
            (let [res (core/eval-sci src {:entries entries})]
              (set-eval-out res)
              (if-let [r (:result res)]
                (if (and (map? r) (= :bar (:chart-type r)))
                  (set-chart-data (:data r))
                  (if (and (map? r) (every? number? (vals r)))
                    (set-chart-data r)
                    (set-chart-data nil)))
                (set-chart-data nil))))]

      (hooks/use-effect
       [entries]
       (run-code code))

      (d/div
       {:class "card"}
       (d/h3 {:class "card-title"} "SCI Analytics Dashboard")
       (d/div
        {:class "form-group"}
        (d/label "Starter Scripts")
        (d/select
         {:on-change
          #(let [v (.. % -target -value)
                 script (get starter-scripts v)]
             (when script
               (set-code script)
               (ls/set-item! :dashboard-code script)
               (run-code script)))}
         (for [k (keys starter-scripts)]
           (d/option {:key k :value k} k))))

       (d/div
        {:class "form-group"}
        (d/label "Clojure Code (SCI)")
        (d/textarea
         {:class "code-editor"
          :value code
          :rows 5
          :on-change
          #(let [v (.. % -target -value)]
             (set-code v)
             (ls/set-item! :dashboard-code v))}))

       (d/button
        {:class "btn btn-primary"
         :on-click #(run-code code)}
        "Run Code")

       (when chart-data
         ($ BarChart {:data chart-data}))

       (when eval-out
         (d/div
          {:class "eval-result"}
          (if-let [err (:error eval-out)]
            (d/span {:style {:color "var(--danger)"}} (str "Error: " err))
            (with-out-str (pp/pprint (:result eval-out))))))))))

(defnc SettingsView [{:keys [config on-save-config on-sync sync-status]}]
  (let [[server-url set-server-url]
        (hooks/use-state (or (:server-url config) "http://localhost:8000"))
        [user-id set-user-id]
        (hooks/use-state (or (:user-id config) "user1"))
        [username set-username]
        (hooks/use-state (or (:username config) "user1"))
        [password set-password]
        (hooks/use-state (or (:password config) "password"))]

    (d/div
     {:class "card"}
     (d/h3 {:class "card-title"} "Backend Sync & Settings")

     (d/div
      {:class "form-group"}
      (d/label "Backend URL")
      (d/input {:value server-url
                :on-change #(set-server-url (.. % -target -value))}))

     (d/div
      {:class "form-group"}
      (d/label "Sync ID / Device ID")
      (d/input {:value user-id
                :on-change #(set-user-id (.. % -target -value))}))

     (d/div
      {:class "form-group"}
      (d/label "Username")
      (d/input {:value username
                :on-change #(set-username (.. % -target -value))}))

     (d/div
      {:class "form-group"}
      (d/label "Password")
      (d/input {:type "password"
                :value password
                :on-change #(set-password (.. % -target -value))}))

     (d/div
      {:style {:display "flex" :gap "8px" :marginTop "12px"}}
      (d/button
       {:class "btn btn-primary"
        :on-click
        #(let [new-cfg {:server-url server-url
                        :user-id user-id
                        :username username
                        :password password}]
           (on-save-config new-cfg))}
       "Save Settings")

      (d/button
       {:class "btn btn-secondary"
        :on-click on-sync}
       "Sync Now"))

     (when sync-status
       (d/div {:style {:marginTop "12px" :fontSize "0.9rem"}}
              sync-status)))))

(defnc AppRoot []
  (let [[active-tab set-active-tab] (hooks/use-state :entries)
        [entries set-entries]
        (hooks/use-state (or (ls/get-item :journal-entries) []))
        [pending-changes set-pending-changes]
        (hooks/use-state (or (ls/get-item :pending-changes) []))
        [editing-entry set-editing-entry] (hooks/use-state nil)
        [config set-config]
        (hooks/use-state (or (ls/get-item :sync-config)
                             {:server-url "http://localhost:8000"
                              :user-id "demo"
                              :username "demo"
                              :password "demo"}))
        [sync-status set-sync-status] (hooks/use-state nil)]

    (let [save-entries!
          (fn [new-entries new-changes]
            (set-entries new-entries)
            (ls/set-item! :journal-entries new-entries)
            (when new-changes
              (set-pending-changes new-changes)
              (ls/set-item! :pending-changes new-changes)))

          do-sync!
          (fn []
            (set-sync-status "Syncing with backend...")
            (go
              (try
                (let [url (str (:server-url config)
                               "/storage/api/sync/"
                               (:user-id config))
                      resp
                      (<! (http/post
                           url
                           {:basic-auth {:username (:username config)
                                         :password (:password config)}
                            :json-params {:changes pending-changes}}))]
                  (if (= 200 (:status resp))
                    (let [remote-entries (get-in resp [:body :entries])]
                      (save-entries! remote-entries [])
                      (set-sync-status (str "Synced successfully at "
                                            (core/now-iso-str))))
                    (set-sync-status (str "Sync failed: status "
                                          (:status resp)))))
                (catch :default e
                  (set-sync-status (str "Sync error: " (.-message e)))))))]

      (d/div
       {:class "container"}
       (d/header
        (d/h1 "Structured Data Journal")
        (d/div
         (if (seq pending-changes)
           (d/span {:class "badge"
                    :style {:background "var(--danger)" :color "#fff"}}
                   (str (count pending-changes) " unsynced"))
           (d/span {:class "badge"
                    :style {:background "var(--success)" :color "#fff"}}
                   "Synced"))))

       (d/nav
        {:class "nav-tabs"}
        (d/button {:class (str "tab-btn " (when (= active-tab :entries) "active"))
                   :on-click #(set-active-tab :entries)}
                  "Journal")
        (d/button {:class (str "tab-btn " (when (= active-tab :new) "active"))
                   :on-click #(do (set-editing-entry nil)
                                  (set-active-tab :new))}
                  "+ New")
        (d/button {:class (str "tab-btn " (when (= active-tab :dashboard) "active"))
                   :on-click #(set-active-tab :dashboard)}
                  "Dashboard")
        (d/button {:class (str "tab-btn " (when (= active-tab :settings) "active"))
                   :on-click #(set-active-tab :settings)}
                  "Sync"))

       (case active-tab
         :entries
         (d/div
          {:class "card"}
          (d/div {:style {:display "flex"
                          :justify-content "space-between"
                          :align-items "center"
                          :marginBottom "12px"}}
                 (d/h3 {:class "card-title" :style {:margin 0}}
                       (str "Entries (" (count entries) ")"))
                 (d/button {:class "btn btn-primary btn-small"
                            :on-click #(set-active-tab :new)}
                           "+ Add Entry"))
          (if (empty? entries)
            (d/p {:style {:color "var(--muted)" :padding "16px 0"}}
                 "No journal entries recorded yet. Click '+ Add Entry' to create one.")
            (for [entry (sort-by :timestamp #(compare %2 %1) entries)]
              (d/div
               {:key (:id entry) :class "entry-list-item"}
               (d/div
                {:class "entry-header"}
                (d/span (:timestamp entry))
                (d/div
                 (d/button
                  {:class "btn btn-secondary btn-small"
                   :style {:marginRight "6px"}
                   :on-click #(do (set-editing-entry entry)
                                  (set-active-tab :new))}
                  "Edit")
                 (d/button
                  {:class "btn btn-danger btn-small"
                   :on-click
                   #(let [id (:id entry)
                          del-change {:id id :deleted? true}
                          remaining (vec (remove (fn [e] (= (:id e) id)) entries))
                          new-changes (conj pending-changes del-change)]
                      (save-entries! remaining new-changes))}
                  "Delete")))
               (d/div {:class "entry-desc"} (:description entry))
               (when (seq (:data entry))
                 (d/div
                  {:class "entry-kv-list"}
                  (for [[k v] (:data entry)]
                    (d/span {:key (str k) :class "badge"}
                            (str (name k) ": " v)))))))))

         :new
         ($ EntryForm
            {:initial-entry editing-entry
             :all-entries entries
             :on-cancel #(set-active-tab :entries)
             :on-save
             (fn [entry]
               (let [id (:id entry)
                     updated-entries
                     (if (some (fn [e] (= (:id e) id)) entries)
                       (mapv (fn [e] (if (= (:id e) id) entry e)) entries)
                       (conj entries entry))
                     updated-changes (conj pending-changes entry)]
                 (save-entries! updated-entries updated-changes)
                 (set-editing-entry nil)
                 (set-active-tab :entries)))})

         :dashboard
         ($ DashboardView {:entries entries})

         :settings
         ($ SettingsView
            {:config config
             :sync-status sync-status
             :on-save-config
             (fn [new-cfg]
               (set-config new-cfg)
               (ls/set-item! :sync-config new-cfg)
               (set-sync-status "Settings saved."))
             :on-sync do-sync!})

         nil)))))
