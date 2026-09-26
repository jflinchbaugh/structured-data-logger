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
            [structured-data-logger.localstorage :as ls]
            [structured-data-logger.version :as version]))

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

(defn parse-val [v]
  (let [trimmed (str/trim (str v))]
    (cond
      (re-matches #"^-?\d+$" trimmed) (js/parseInt trimmed 10)
      (re-matches #"^-?\d+\.\d+$" trimmed) (js/parseFloat trimmed)
      :else trimmed)))

(defn format-kv
  "Formats a key and value for display. When a key doesn't have a value,
   does not show the ':'."
  [k v]
  (let [k-str (name k)
        v-str (when (some? v) (str v))]
    (if (or (nil? v-str) (str/blank? v-str))
      k-str
      (str k-str ": " v-str))))

(defn confirm-dialog
  "Prompts the user with a confirmation dialog. Defaults to js/window.confirm."
  [msg]
  (if (exists? js/window)
    (js/confirm msg)
    false))

(defn confirm-delete?
  "Asks confirmation to delete an entry."
  [_entry]
  (confirm-dialog "Are you sure you want to delete this entry?"))

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
        [error-msg set-error-msg] (hooks/use-state nil)
        blended-k (core/blended-keys all-entries)
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
                :step "1"
                :value timestamp
                :on-change #(set-timestamp (.. % -target -value))}))

     (d/div
      {:class "form-group"}
      (d/label "Description")
      (d/textarea {:rows 5
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
                        :placeholder "key-name"
                        :list "known-keys-datalist"
                        :auto-capitalize "none"
                        :auto-correct "off"
                        :spell-check false
                        :on-change
                        #(let [v (core/format-key-input (.. % -target -value))]
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
                 :auto-capitalize "none"
                 :auto-correct "off"
                 :spell-check false
                 :on-change #(set-new-key-name
                              (core/format-key-input (.. % -target -value)))})
       (d/input {:placeholder "Value (numeric, text)"
                 :value new-key-val
                 :list "new-key-values-datalist"
                 :on-change #(set-new-key-val (.. % -target -value))})
       (d/button
        {:class "btn btn-secondary"
         :on-click
         #(when (not (str/blank? new-key-name))
            (set-kv-list (conj kv-list {:key (core/to-kebab-case new-key-name)
                                        :val (str/trim new-key-val)}))
            (set-new-key-name "")
            (set-new-key-val ""))}
        "+ Add"))

      (when (seq blended-k)
        (d/div
         (d/span {:style {:fontSize "0.8rem" :color "var(--muted)"}}
                 "Suggested keys:")
         (d/div
          {:class "chip-row"}
          (for [k (take 10 blended-k)]
            (let [k-str (core/to-kebab-case (name k))]
              (d/span {:key (str "key-" k)
                       :class "chip"
                       :on-click #(set-new-key-name k-str)}
                      k-str))))))

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

     (when error-msg
       (d/div {:style {:color "var(--danger)"
                       :marginTop "8px"
                       :fontSize "0.85rem"}}
              error-msg))

     (d/div
      {:style {:display "flex" :gap "8px" :marginTop "16px"}}
      (d/button
       {:class "btn btn-primary"
        :on-click
        #(let [pending-pair (when (not (str/blank? new-key-name))
                              {:key (core/to-kebab-case new-key-name)
                               :val (str/trim new-key-val)})
               effective-kv (if pending-pair
                              (conj kv-list pending-pair)
                              kv-list)
               data-map (into {}
                              (for [{:keys [key val]} effective-kv
                                    :let [k-str (core/to-kebab-case key)]
                                    :when (not (str/blank? k-str))]
                                [(keyword k-str) (parse-val val)]))
               desc (str/trim (or description ""))]
           (if (and (str/blank? desc) (empty? data-map))
             (set-error-msg
              "Please provide a description or at least one field.")
             (let [entry (core/create-entry
                          {:id (:id initial-entry)
                           :timestamp (core/from-local-datetime-input timestamp)
                           :description desc
                           :data data-map})]
               (set-error-msg nil)
               (on-save entry))))}
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

(defnc SettingsView
  [{:keys [config pending-ops on-save-config on-register on-sync sync-status]}]
  (let [[user-id set-user-id]
        (hooks/use-state (or (:user-id config) ""))
        [username set-username]
        (hooks/use-state (or (:username config) ""))
        [password set-password]
        (hooks/use-state (or (:password config) ""))
        pending-count (count (or pending-ops []))
        dirty? (core/sync-settings-dirty? config {:user-id user-id
                                                  :username username
                                                  :password password})]

    (d/div
     {:class "card"}
     (d/div
      {:style {:display "flex"
               :justify-content "space-between"
               :align-items "center"
               :marginBottom "12px"}}
      (d/h3 {:class "card-title" :style {:margin 0}}
            "Backend Sync & Settings")
      (if (pos? pending-count)
        (d/span {:class "badge"
                 :style {:background "var(--danger)" :color "#fff"}}
                (str pending-count " unsynced"))
        (d/span {:class "badge"
                 :style {:background "var(--success)" :color "#fff"}}
                "Synced")))

     (d/div
      {:class "form-group"}
      (d/label "Sync ID / Device ID")
      (d/input {:value user-id
                :placeholder "e.g. my-device"
                :on-change #(set-user-id (.. % -target -value))}))

     (d/div
      {:class "form-group"}
      (d/label "Email Address (Username)")
      (d/input {:type "email"
                :value username
                :placeholder "user@example.com"
                :on-change #(set-username (.. % -target -value))}))

     (d/div
      {:class "form-group"}
      (d/label "Password")
      (d/input {:type "password"
                :value password
                :placeholder "Password"
                :on-change #(set-password (.. % -target -value))}))

     (d/div
      {:style {:display "flex"
               :gap "8px"
               :flexWrap "wrap"
               :marginTop "12px"}}
      (d/button
       {:class (str "btn btn-primary" (when dirty? " btn-highlight"))
        :on-click
        #(let [new-cfg {:user-id user-id
                        :username username
                        :password password}]
           (on-save-config new-cfg))}
       (if dirty? "Save Settings *" "Save Settings"))

      (d/button
       {:class "btn btn-secondary"
        :disabled dirty?
        :title (when dirty? "Save settings before registering")
        :on-click
        #(let [cfg {:user-id user-id
                    :username username
                    :password password}]
           (on-register cfg))}
       "Register Account")

      (d/button
       {:class "btn btn-secondary"
        :disabled dirty?
        :title (when dirty? "Save settings before syncing")
        :on-click #(when on-sync (on-sync "Syncing with backend..."))}
       "Sync Now"))

     (when sync-status
       (d/div {:style {:marginTop "12px" :fontSize "0.9rem"}}
              sync-status))

     (d/div {:class "build-info"}
            (str "Build: " version/build-date)))))

(defn current-window-tab
  "Reads the active tab from window.location.hash."
  []
  (if (exists? js/window)
    (core/hash->tab (.-hash (.-location js/window)))
    :entries))

(defn set-window-hash!
  "Updates window.location.hash to reflect the active tab."
  [tab]
  (when (exists? js/window)
    (let [h (core/tab->hash tab)]
      (when (not= (.-hash (.-location js/window)) h)
        (set! (.-hash (.-location js/window)) h)))))

(defnc EntryListItem
  [{:keys [entry on-edit on-delete]}]
  (d/div
   {:class "entry-list-item"}
   (d/div
    {:class "entry-header"}
    (d/span (core/format-local-datetime (:timestamp entry)))
    (d/div
     (d/a
      {:class "btn btn-secondary btn-small"
       :href "#new"
       :style {:marginRight "6px"}
       :on-click #(when on-edit (on-edit entry))}
      "Edit")
     (d/button
      {:class "btn btn-danger btn-small"
       :on-click #(when on-delete (on-delete entry))}
      "Delete")))
   (d/div {:class "entry-desc"} (:description entry))
   (when (seq (:data entry))
     (d/div
      {:class "entry-kv-list"}
      (for [[k v] (:data entry)]
        (d/span {:key (str k) :class "badge"}
                (format-kv k v)))))))

(defnc EntriesListView
  [{:keys [entries on-add on-edit on-delete]}]
  (let [clean-entries (filterv core/valid-entry? (or entries []))]
    (d/div
     {:id "entries" :class "card"}
     (d/div {:style {:display "flex"
                     :justify-content "space-between"
                     :align-items "center"
                     :marginBottom "12px"}}
            (d/h3 {:class "card-title" :style {:margin 0}}
                  (str "Entries (" (count clean-entries) ")"))
            (d/a {:class "btn btn-primary btn-small"
                  :href "#new"
                  :on-click #(when on-add (on-add))}
                 "+ Add Entry"))
     (if (empty? clean-entries)
       (d/p {:style {:color "var(--muted)" :padding "16px 0"}}
            (str "No journal entries recorded yet. "
                 "Click '+ Add Entry' to create one."))
       (for [entry (sort-by :timestamp #(compare %2 %1) clean-entries)]
         ($ EntryListItem
            {:key (:id entry)
             :entry entry
             :on-edit on-edit
             :on-delete on-delete}))))))

(defnc AppRoot []
  (let [[active-tab set-active-tab] (hooks/use-state current-window-tab)
        [entries set-entries]
        (hooks/use-state (filterv core/valid-entry?
                                  (or (ls/get-item :journal-entries) [])))
        [pending-ops set-pending-ops]
        (hooks/use-state (vec (filter #(and (map? %)
                                            (string? (:client-tx-id %))
                                            (not (str/blank?
                                                  (:client-tx-id %))))
                                      (or (ls/get-item :pending-ops)
                                          (ls/get-item :pending-changes)
                                          []))))
        [last-tx-id set-last-tx-id]
        (hooks/use-state (or (ls/get-item :last-tx-id) 0))
        [editing-entry set-editing-entry] (hooks/use-state nil)
        [config set-config]
        (hooks/use-state (or (ls/get-item :sync-config)
                             {:user-id ""
                              :username ""
                              :password ""}))
        [sync-status set-sync-status] (hooks/use-state nil)
        syncing-ref (hooks/use-ref false)
        state-ref (hooks/use-ref nil)]

    (set! (.-current state-ref)
          {:entries entries
           :pending-ops pending-ops
           :last-tx-id last-tx-id
           :config config})

    (let [save-local!
          (fn [new-entries new-ops]
            (let [clean-entries (filterv core/valid-entry? (or new-entries []))]
              (set-entries clean-entries)
              (ls/set-item! :journal-entries clean-entries)
              (when new-ops
                (set-pending-ops new-ops)
                (ls/set-item! :pending-ops new-ops))))

          do-sync!
          (fn [& [status-msg force-since-tx-id]]
            (let [msg (when (string? status-msg) status-msg)]
              (when-not (.-current syncing-ref)
                (set! (.-current syncing-ref) true)
                (let [{:keys [entries pending-ops last-tx-id config]}
                      (.-current state-ref)]
                  (if (or (str/blank? (:user-id config))
                          (not (core/valid-email? (:username config))))
                    (do
                      (set! (.-current syncing-ref) false)
                      (when (and msg (not= msg "Syncing on startup..."))
                        (set-sync-status
                         "Configure email username & Sync ID to enable sync.")))
                    (do
                      (when msg
                        (set-sync-status msg))
                      (go
                      (try
                        (let [in-flight (or pending-ops [])
                              url (str "/journal/api/sync/"
                                       (:user-id config))
                              payload (core/prepare-sync-payload
                                       {:last-tx-id last-tx-id
                                        :pending-ops in-flight
                                        :force-since-tx-id
                                        force-since-tx-id})
                              resp
                              (<! (http/post
                                   url
                                   {:basic-auth {:username (:username config)
                                                 :password (:password config)}
                                    :json-params payload}))]
                          (if (= 200 (:status resp))
                            (let [body (:body resp)
                                  server-last-tx (or (:last-tx-id body)
                                                     last-tx-id)
                                  server-txs (or (:transactions body) [])
                                  cur-pending (or (:pending-ops
                                                   (.-current state-ref))
                                                  pending-ops)
                                  cur-entries (or (:entries
                                                   (.-current state-ref))
                                                  entries)
                                  reconciled (core/reconcile-client-state
                                              {:entries cur-entries
                                               :pending-ops cur-pending
                                               :in-flight-ops in-flight
                                               :received-txs server-txs})
                                  clean (filterv core/valid-entry?
                                                 (:entries reconciled))]
                              (set-entries clean)
                              (set-pending-ops (:pending-ops reconciled))
                              (set-last-tx-id server-last-tx)
                              (ls/set-item! :journal-entries clean)
                              (ls/set-item! :pending-ops
                                            (:pending-ops reconciled))
                              (ls/set-item! :last-tx-id server-last-tx)
                              (set-sync-status
                               (str "Synced successfully at "
                                    (core/now-iso-str))))
                            (set-sync-status (str "Sync failed: status "
                                                  (:status resp)))))
                        (catch :default e
                          (set-sync-status (str "Sync error: "
                                                (.-message e))))
                        (finally
                          (set! (.-current syncing-ref) false))))))))))

          do-register!
          (fn [{:keys [user-id username password]}]
            (cond
              (str/blank? user-id)
              (set-sync-status "Please provide a Sync ID.")

              (not (core/valid-email? username))
              (set-sync-status
               "Username must be a valid email address.")

              (str/blank? password)
              (set-sync-status "Please provide a password.")

              :else
              (do
                (set-sync-status "Registering account with backend...")
                (go
                  (try
                    (let [url "/journal/api/register"
                          resp
                          (<! (http/post
                               url
                               {:json-params {:id user-id
                                              :login username
                                              :password password}}))]
                      (if (= 200 (:status resp))
                        (do
                          (let [new-cfg {:user-id user-id
                                         :username username
                                         :password password}]
                            (set-config new-cfg)
                            (ls/set-item! :sync-config new-cfg)
                            (set-last-tx-id 0)
                            (ls/set-item! :last-tx-id 0)
                            (when (.-current state-ref)
                              (set! (.-current state-ref)
                                    (assoc (.-current state-ref)
                                           :config new-cfg
                                           :last-tx-id 0))))
                          (set-sync-status (str "Registered: " (:body resp)))
                          (js/setTimeout
                           #(do-sync! "Syncing full history..." 0) 50))
                        (set-sync-status (str "Registration failed: "
                                              (:body resp)))))
                    (catch :default e
                      (set-sync-status (str "Registration error: "
                                            (.-message e)))))))))

          navigate-to!
          (fn [tab]
            (set-active-tab tab)
            (set-window-hash! tab))

          delete-entry!
          (fn [entry]
            (when (confirm-delete? entry)
              (let [clean-entries (filterv core/valid-entry? (or entries []))
                    id (:id entry)
                    del-op (when (and (string? id) (not (str/blank? id)))
                             (core/create-delete-op id))
                    updated-entries (if del-op
                                      (core/apply-transaction
                                       clean-entries del-op)
                                      clean-entries)
                    updated-ops (if del-op
                                  (conj pending-ops del-op)
                                  pending-ops)]
                (save-local! updated-entries updated-ops)
                (js/setTimeout do-sync! 50))))]

      ;; 1. Sync on mount / startup
      (hooks/use-effect
       :once
       (do-sync! "Syncing on startup...")
       nil)

      ;; 2. Sync on browser reconnect and visibility changes
      (hooks/use-effect
       :once
       (let [on-online (fn [] (do-sync! "Syncing on reconnect..."))
             on-vis (fn []
                      (when (= (.-visibilityState js/document) "visible")
                        (do-sync! "Syncing on focus...")))]
         (.addEventListener js/window "online" on-online)
         (.addEventListener js/document "visibilitychange" on-vis)
         (fn []
           (.removeEventListener js/window "online" on-online)
           (.removeEventListener js/document "visibilitychange" on-vis))))

      ;; 3. Sync active tab with window hash changes (back/forward / links)
      (hooks/use-effect
       :once
       (let [on-hash (fn []
                       (let [t (current-window-tab)]
                         (set-active-tab t)
                         (when (not= t :new)
                           (set-editing-entry nil))))]
         (when (exists? js/window)
           (.addEventListener js/window "hashchange" on-hash)
           (when (str/blank? (.-hash (.-location js/window)))
             (set-window-hash! active-tab)))
         (fn []
           (when (exists? js/window)
             (.removeEventListener js/window "hashchange" on-hash)))))

      (d/div
       {:class "app-container"}
       (d/header
        {:class "app-header"}
        (d/div {:style {:display "flex"
                        :align-items "center"
                        :gap "10px"}}
               (d/img {:src "icon-192.png"
                       :alt "Structured Data Journal Icon"
                       :class "app-logo"})
               (d/h1 {:class "app-title"} "Structured Data Journal")))
       (d/nav
        {:class "nav-tabs"}
        (d/a {:class (str "tab-btn"
                          (when (= active-tab :entries) " active"))
              :href "#entries"
              :on-click #(navigate-to! :entries)}
             "Entries")
        (d/a {:class (str "tab-btn"
                          (when (= active-tab :new) " active"))
              :href "#new"
              :on-click #(do (set-editing-entry nil)
                             (navigate-to! :new))}
             "+ New")
        (d/a {:class (str "tab-btn"
                          (when (= active-tab :dashboard) " active"))
              :href "#dashboard"
              :on-click #(navigate-to! :dashboard)}
             "Dashboard")
        (d/a {:class (str "tab-btn"
                          (when (= active-tab :settings) " active"))
              :href "#settings"
              :on-click #(navigate-to! :settings)}
             "Sync / Settings"))

       (case active-tab
         :entries
         ($ EntriesListView
            {:entries entries
             :on-add #(navigate-to! :new)
             :on-edit (fn [entry]
                        (set-editing-entry entry)
                        (navigate-to! :new))
             :on-delete delete-entry!})

         :new
         (d/div
          {:id "new"}
          ($ EntryForm
             {:initial-entry editing-entry
              :all-entries entries
              :on-cancel #(navigate-to! :entries)
              :on-save
              (fn [entry]
                (let [put-op (core/create-put-op entry)
                      updated-entries (core/apply-transaction entries put-op)
                      updated-ops (conj pending-ops put-op)]
                  (save-local! updated-entries updated-ops)
                  (set-editing-entry nil)
                  (navigate-to! :entries)
                  (js/setTimeout do-sync! 50)))}))

         :dashboard
         (d/div
          {:id "dashboard"}
          ($ DashboardView {:entries entries}))

         :settings
         (d/div
          {:id "settings"}
          ($ SettingsView
             {:config config
              :pending-ops pending-ops
              :sync-status sync-status
              :on-save-config
              (fn [new-cfg]
                (set-config new-cfg)
                (ls/set-item! :sync-config new-cfg)
                (set-last-tx-id 0)
                (ls/set-item! :last-tx-id 0)
                (when (.-current state-ref)
                  (set! (.-current state-ref)
                        (assoc (.-current state-ref)
                               :config new-cfg
                               :last-tx-id 0)))
                (set-sync-status "Settings saved. Syncing full history...")
                (js/setTimeout
                 #(do-sync! "Syncing full history..." 0) 50))
              :on-register do-register!
              :on-sync do-sync!}))

         nil)))))
