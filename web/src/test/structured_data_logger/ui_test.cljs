(ns structured-data-logger.ui-test
  (:require [cljs.test :refer [deftest is testing]]
            [helix.core :refer [$]]
            ["jsdom" :refer [JSDOM]]
            [structured-data-logger.core :as core]
            [structured-data-logger.ui :as ui]))

(defonce ^:private _init-jsdom
  (let [dom (JSDOM. "<!DOCTYPE html><html><body></body></html>"
                    #js {:url "http://localhost/"})]
    (js/goog.object.set js/goog.global "window" (.-window dom))
    (js/goog.object.set js/goog.global "document" (.. dom -window -document))
    (js/Object.defineProperty
     js/goog.global "navigator"
     #js {:value (.. dom -window -navigator)
          :configurable true
          :writable true})))

(deftest parse-val-test
  (testing "parse-val correctly handles integer, float, and string values"
    (is (= 42 (ui/parse-val "42")))
    (is (= -7 (ui/parse-val "-7")))
    (is (= 0 (ui/parse-val "0")))
    (is (= 3.14 (ui/parse-val "3.14")))
    (is (= -0.5 (ui/parse-val "-0.5")))
    (is (= "normal" (ui/parse-val "normal")))
    (is (= "120/80" (ui/parse-val "120/80")))
    (is (= "hello world" (ui/parse-val "  hello world  ")))))

(deftest format-kv-test
  (testing "format-kv displays key and value with colon when value is present"
    (is (= "food: oatmeal" (ui/format-kv :food "oatmeal")))
    (is (= "count: 42" (ui/format-kv :count 42)))
    (is (= "temp: 98.6" (ui/format-kv :temp 98.6)))
    (is (= "active: false" (ui/format-kv :active false))))
  (testing "format-kv displays only key without colon when value is empty"
    (is (= "fasting" (ui/format-kv :fasting nil)))
    (is (= "fasting" (ui/format-kv :fasting "")))
    (is (= "fasting" (ui/format-kv :fasting "   ")))
    (is (= "tag" (ui/format-kv "tag" "")))))

(deftest starter-scripts-validation-test
  (testing "t namespace is available in SCI evaluation"
    (let [res (core/eval-sci "(t/now)" {})]
      (is (nil? (:error res)))
      (is (some? (:result res))))
    (let [res (core/eval-sci "(t/new-duration 7 :days)" {})]
      (is (nil? (:error res)))
      (is (some? (:result res))))
    (let [res (core/eval-sci "(t/- (t/now) (t/new-duration 7 :days))" {})]
      (is (nil? (:error res)))
      (is (some? (:result res))))
    (let [sample [{:timestamp (core/now-iso-str)}]
          res (core/eval-sci
               (str "(let [inst (t/instant (:timestamp (first entries)))]"
                    "  (t/> inst (t/- (t/now) (t/new-duration 7 :days))))")
               {:entries sample})]
      (is (nil? (:error res)))
      (is (= true (:result res)))))
  (let [sample-entries [{:id "1"
                         :timestamp (core/now-iso-str)
                         :description "Breakfast"
                         :data {:food "oatmeal"
                                :mileage 15
                                :pills "aspirin"}}
                        {:id "2"
                         :timestamp (core/now-iso-str)
                         :description "Lunch"
                         :data {:food "salad"
                                :mileage 25
                                :pills "vitamin"}}]]
    (doseq [[script-name script] ui/starter-scripts]
      (testing (str "starter script: " script-name)
        (let [res (core/eval-sci script {:entries sample-entries})]
          (is (nil? (:error res))
              (str script-name " failed with error: " (:error res)))
          (is (some? (:result res))
              (str script-name " returned nil result"))
          (when (= script-name "Recent Window (Last 7 Days)")
            (is (= 2 (count (:result res)))
                "Recent Window should match current entries")))))))

(deftest window-tab-hash-test
  (testing "current-window-tab defaults to :entries when window has no hash"
    (is (= :entries (ui/current-window-tab))))
  (testing "window hash synchronization with mock window"
    (let [fake-location #js {:hash "#dashboard"}
          fake-window #js {:location fake-location}]
      (js/goog.object.set js/goog.global "window" fake-window)
      (try
        (is (= :dashboard (ui/current-window-tab)))
        (ui/set-window-hash! :settings)
        (is (= "#settings" (.-hash fake-location)))
        (ui/set-window-hash! :entries)
        (is (= "#entries" (.-hash fake-location)))
        (finally
          ;; Restore window to JSDOM window instead of deleting
          (let [dom (js/require "jsdom")
                jsdom (.-JSDOM dom)
                d (jsdom. "<!DOCTYPE html><html><body></body></html>"
                          #js {:url "http://localhost/"})]
            (js/goog.object.set js/goog.global "window" (.-window d))))))))

(deftest entry-list-item-rtl-test
  (testing "EntryListItem renders text content and triggers on-edit / on-delete"
    (let [rtl (js/require "@testing-library/react")
          render (.-render rtl)
          screen (.-screen rtl)
          fire-event (.-fireEvent rtl)
          sample {:id "e1"
                  :timestamp "2026-09-10T12:00:00Z"
                  :description "Morning run"
                  :data {:miles 3.5 :fasting nil}}
          edited (atom nil)
          deleted (atom nil)
          view (render ($ ui/entry-list-item
                          {:entry sample
                           :on-edit #(reset! edited %)
                           :on-delete #(reset! deleted %)}))]
      (try
        (is (some? (.getByText screen "Morning run")))
        (is (some? (.getByText screen "miles: 3.5")))
        (is (some? (.getByText screen "fasting")))
        (.click fire-event (.getByText screen "Edit"))
        (is (= sample @edited))
        ;; First click swaps button text to "Really?" without deleting
        (.click fire-event (.getByText screen "Delete"))
        (is (nil? @deleted))
        (is (some? (.getByText screen "Really?")))
        ;; Clicking elsewhere resets "Really?" back to "Delete"
        (.click fire-event (.getByText screen "Morning run"))
        (is (nil? @deleted))
        (is (nil? (.queryByText screen "Really?")))
        (is (some? (.getByText screen "Delete")))
        ;; Second click on "Really?" confirms deletion
        (.click fire-event (.getByText screen "Delete"))
        (.click fire-event (.getByText screen "Really?"))
        (is (= sample @deleted))
        (finally
          ((js/goog.object.get view "unmount")))))))

(deftest entries-list-view-rtl-test
  (testing "EntriesListView renders empty state without header or Add button"
    (let [rtl (js/require "@testing-library/react")
          render (.-render rtl)
          screen (.-screen rtl)
          view (render ($ ui/entries-list-view {:entries []}))]
      (try
        (is (nil? (.queryByText screen "Entries (0)")))
        (is (some? (.getByText
                    screen
                    (str "No journal entries recorded yet. "
                         "Click '+ New' to create one."))))
        (is (nil? (.queryByText screen "+ Add Entry")))
        (finally
          ((js/goog.object.get view "unmount"))))))

  (testing "EntriesListView renders list of entries without header"
    (let [rtl (js/require "@testing-library/react")
          render (.-render rtl)
          screen (.-screen rtl)
          entries [{:id "1"
                    :timestamp "2026-09-01T10:00:00Z"
                    :description "Older Entry"}
                   {:id "2"
                    :timestamp "2026-09-15T10:00:00Z"
                    :description "Newer Entry"}]
          view (render ($ ui/entries-list-view {:entries entries}))]
      (try
        (is (nil? (.queryByText screen "Entries (2)")))
        (is (some? (.getByText screen "Older Entry")))
        (is (some? (.getByText screen "Newer Entry")))
        (finally
          ((js/goog.object.get view "unmount"))))))

  (testing "AppRoot displays entries count in the Entries nav tab button"
    (let [rtl (js/require "@testing-library/react")
          render (.-render rtl)
          screen (.-screen rtl)
          view (render ($ ui/app-root))]
      (try
        (is (some? (.getByText screen "Entries (0)")))
        (finally
          ((js/goog.object.get view "unmount")))))))

(deftest entry-form-subcomponents-test
  (testing "EntryKvRow and EntryAddField are exposed as decomposed components"
    (is (fn? ui/entry-kv-row))
    (is (fn? ui/entry-add-field)))

  (testing "EntryKvRow renders inputs and triggers callbacks"
    (let [rtl (js/require "@testing-library/react")
          render (.-render rtl)
          screen (.-screen rtl)
          fire-event (.-fireEvent rtl)
          changed (atom nil)
          removed (atom nil)
          view (render ($ ui/entry-kv-row
                          {:idx 0
                           :item {:key "pills" :val "aspirin"}
                           :all-entries []
                           :on-change (fn [idx field val]
                                        (reset! changed [idx field val]))
                           :on-remove (fn [idx] (reset! removed idx))}))]
      (try
        (let [key-input (.getByPlaceholderText screen "key-name")
              val-input (.getByPlaceholderText screen "Value")]
          (is (= "pills" (.-value key-input)))
          (is (= "aspirin" (.-value val-input)))
          (.change fire-event val-input #js {:target #js {:value "ibuprofen"}})
          (is (= [0 :val "ibuprofen"] @changed))
          (.click fire-event (.getByText screen ui/close-symbol))
          (is (= 0 @removed)))
        (finally
          ((js/goog.object.get view "unmount"))))))

  (testing "EntryAddField renders and triggers on-add"
    (let [rtl (js/require "@testing-library/react")
          render (.-render rtl)
          screen (.-screen rtl)
          fire-event (.-fireEvent rtl)
          added (atom false)
          view (render ($ ui/entry-add-field
                          {:all-entries []
                           :new-key-name "mileage"
                           :set-new-key-name (fn [_])
                           :new-key-val "25"
                           :set-new-key-val (fn [_])
                           :on-add #(reset! added true)}))]
      (try
        (is (some? (.getByText screen "+ Add")))
        (.click fire-event (.getByText screen "+ Add"))
        (is (true? @added))
        (finally
          ((js/goog.object.get view "unmount"))))))

  (testing "EntryAddField focuses value field when a suggested key is clicked"
    (let [rtl (js/require "@testing-library/react")
          render (.-render rtl)
          screen (.-screen rtl)
          fire-event (.-fireEvent rtl)
          key-val (atom "")
          view (render ($ ui/entry-add-field
                          {:all-entries [{:id "1"
                                          :timestamp "2026-09-01T10:00:00Z"
                                          :description "test"
                                          :data {:pills "aspirin"}}]
                           :new-key-name @key-val
                           :set-new-key-name #(reset! key-val %)
                           :new-key-val ""
                           :set-new-key-val (fn [_])
                           :on-add (fn [])}))]
      (try
        (let [chip (.getByText screen "pills")
              val-input (.getByPlaceholderText screen "Value (numeric, text)")]
          (is (not= (.-activeElement js/document) val-input))
          (.click fire-event chip)
          (is (= "pills" @key-val))
          (is (= (.-activeElement js/document) val-input)))
        (finally
          ((js/goog.object.get view "unmount"))))))

  (testing "EntryAddField does NOT focus value field on typing a matching key"
    (let [rtl (js/require "@testing-library/react")
          render (.-render rtl)
          screen (.-screen rtl)
          fire-event (.-fireEvent rtl)
          key-val (atom "")
          view (render ($ ui/entry-add-field
                          {:all-entries [{:id "1"
                                          :timestamp "2026-09-01T10:00:00Z"
                                          :description "test"
                                          :data {:test 1 :test-xyz 2}}]
                           :new-key-name @key-val
                           :set-new-key-name #(reset! key-val %)
                           :new-key-val ""
                           :set-new-key-val (fn [_])
                           :on-add (fn [])}))]
      (try
        (let [key-input (.getByPlaceholderText
                         screen "Key name (e.g. pills, mileage)")
              val-input (.getByPlaceholderText screen "Value (numeric, text)")]
          (.focus key-input)
          (is (= (.-activeElement js/document) key-input))
          ;; Typing 'test' with insertText InputEvent
          (.input fire-event key-input
                  #js {:target #js {:value "test"}
                       :inputType "insertText"})
          (is (not= (.-activeElement js/document) val-input))
          (is (= (.-activeElement js/document) key-input)))
        (finally
          ((js/goog.object.get view "unmount"))))))

  (testing "EntryAddField focuses value field when selecting from datalist"
    (let [rtl (js/require "@testing-library/react")
          render (.-render rtl)
          screen (.-screen rtl)
          fire-event (.-fireEvent rtl)
          key-val (atom "")
          view (render ($ ui/entry-add-field
                          {:all-entries [{:id "1"
                                          :timestamp "2026-09-01T10:00:00Z"
                                          :description "test"
                                          :data {:test 1 :test-xyz 2}}]
                           :new-key-name @key-val
                           :set-new-key-name #(reset! key-val %)
                           :new-key-val ""
                           :set-new-key-val (fn [_])
                           :on-add (fn [])}))]
      (try
        (let [key-input (.getByPlaceholderText
                         screen "Key name (e.g. pills, mileage)")
              val-input (.getByPlaceholderText screen "Value (numeric, text)")]
          (.focus key-input)
          ;; Selecting from datalist dropdown: inputType is undefined (Chrome)
          ;; or insertReplacementText (Firefox)
          (.input fire-event key-input
                  #js {:target #js {:value "test"}
                       :inputType "insertReplacementText"})
          (is (= (.-activeElement js/document) val-input)))
        (finally
          ((js/goog.object.get view "unmount"))))))

  (testing "EntryAddField provides GPS button that populates coordinates"
    (let [rtl (js/require "@testing-library/react")
          render (.-render rtl)
          screen (.-screen rtl)
          fire-event (.-fireEvent rtl)
          new-k (atom "")
          new-v (atom "")
          mock-nav #js {:geolocation
                        #js {:getCurrentPosition
                             (fn [cb _]
                               (cb #js {:coords
                                        #js {:latitude 37.7749
                                             :longitude -122.4194}}))}}
          view (render ($ ui/entry-add-field
                          {:all-entries []
                           :new-key-name @new-k
                           :set-new-key-name #(reset! new-k %)
                           :new-key-val @new-v
                           :set-new-key-val #(reset! new-v %)
                           :navigator-obj mock-nav
                           :on-add (fn [])}))]
      (try
        (let [gps-btn (.getByTitle screen "Use current GPS location")]
          (is (some? gps-btn))
          (.click fire-event gps-btn)
          (is (= "location" @new-k))
          (is (= "37.774900, -122.419400" @new-v)))
        (finally
          ((js/goog.object.get view "unmount"))))))

  (testing (str "get-browser-geolocation falls back to low-accuracy "
                "wifi/network when high accuracy fails")
    (let [calls (atom [])
          mock-nav #js {:geolocation
                        #js {:getCurrentPosition
                             (fn [cb err-cb opts]
                               (let [high? (.-enableHighAccuracy opts)]
                                 (swap! calls conj high?)
                                 (if high?
                                   (err-cb
                                    #js {:code 2
                                         :message "Position unavailable"})
                                   (cb #js {:coords
                                            #js {:latitude 37.7749
                                                 :longitude -122.4194}}))))}}
          result (atom nil)]
      (ui/get-browser-geolocation mock-nav #(reset! result %) (fn [_]))
      (is (= [true false] @calls))
      (is (= "37.774900, -122.419400" @result)))))


(deftest format-kv-component-test
  (testing "format-kv-component renders a link for location keys"
    (let [rtl (js/require "@testing-library/react")
          render (.-render rtl)
          screen (.-screen rtl)
          view (render ($ ui/format-kv-component
                          {:k :location :v "37.774900, -122.419400"}))]
      (try
        (let [link (.getByRole screen "link")]
          (is (some? link))
          (is (= (str "https://www.google.com/maps/search/?api=1&"
                      "query=37.774900,-122.419400")
                 (.-href link))))
        (finally
          ((js/goog.object.get view "unmount"))))))

  (testing "format-kv-component renders regular text for non-location keys"
    (let [rtl (js/require "@testing-library/react")
          render (.-render rtl)
          screen (.-screen rtl)
          view (render ($ ui/format-kv-component
                          {:k :food :v "oatmeal"}))]
      (try
        (is (some? (.getByText screen "food: oatmeal")))
        (finally
          ((js/goog.object.get view "unmount")))))))
