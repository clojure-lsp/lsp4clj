(ns lsp4clj.coercer
  (:require
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [lsp4clj.lsp.errors :as lsp.errors]))

(set! *warn-on-reflection* true)

(def file-change-type-enum {1 :created 2 :changed 3 :deleted})
(s/def :file-event/type (s/and int?
                               file-change-type-enum
                               (s/conformer file-change-type-enum)))
(s/def ::file-event (s/keys :req-un [::uri :file-event/type]))
(s/def :did-change-watched-files/changes (s/coll-of ::file-event))
(s/def ::did-change-watched-files-params (s/keys :req-un [:did-change-watched-files/changes]))

(s/def :error/code (s/and (s/or :kw keyword? :int int?)
                          (s/conformer second)))
(s/def :error/message string?)

(s/def ::error (s/keys :req-un [:error/code :error/message]
                       :opt-un [::data]))

(s/def ::response-error (s/and (s/keys :req-un [::error])
                               (s/conformer
                                 (fn [resp]
                                   (update resp :error
                                           (fn [{:keys [code message data]}]
                                             (lsp.errors/body code message data)))))))

(s/def ::line (s/and integer? (s/conformer int)))
(s/def ::character (s/and integer? (s/conformer int)))
(s/def ::position (s/keys :req-un [::line ::character]))
(s/def ::start ::position)
(s/def ::end ::position)
(s/def ::range (s/keys :req-un [::start ::end]))
(s/def ::selection-range ::range)

(def completion-item-kind->enum-val
  {:text 1 :method 2 :function 3 :constructor 4 :field 5 :variable 6 :class 7 :interface 8 :module 9
   :property 10 :unit 11 :value 12 :enum 13 :keyword 14 :snippet 15 :color 16 :file 17 :reference 18
   :folder 19 :enummember 20 :constant 21 :struct 22 :event 23 :operator 24 :typeparameter 25})

(def enum-val->completion-item-kind
  (set/map-invert completion-item-kind->enum-val))

(s/def :completion-item/kind (s/and keyword?
                                    completion-item-kind->enum-val
                                    (s/conformer completion-item-kind->enum-val)))

(def insert-text-format->enum-val
  {:plaintext 1
   :snippet 2})

(def enum-val->insert-text-format
  (set/map-invert insert-text-format->enum-val))

(s/def :completion-item/insert-text-format
  (s/and keyword?
         insert-text-format->enum-val
         (s/conformer insert-text-format->enum-val)))

(s/def ::new-text string?)
(s/def ::text-edit (s/keys :req-un [::new-text ::range]))
(s/def ::additional-text-edits (s/coll-of ::text-edit))
(s/def ::documentation (s/and (s/or :string string?
                                    :markup-content ::markup-content)
                              (s/conformer second)))

(s/def :prepare-rename/placeholder string?)
(s/def ::prepare-rename (s/keys :req-un [:prepare-rename/placeholder ::range]))

(s/def ::prepare-rename-or-error
  (s/and (s/or :error ::response-error
               :range ::prepare-rename
               :start ::range)
         (s/conformer second)))

(s/def ::completion-item (s/keys :req-un [::label]
                                 :opt-un [::additional-text-edits ::filter-text ::detail ::text-edit
                                          :completion-item/kind ::documentation ::data
                                          ::insert-text :completion-item/insert-text-format]))
(s/def ::completion-item-or-error
  (s/and (s/or :error ::response-error
               :completion-item ::completion-item)
         (s/conformer second)))

(s/def ::completion-items (s/coll-of ::completion-item))
(s/def ::completion-items-or-error
  (s/and (s/or :error ::response-error
               :completion-items ::completion-items)
         (s/conformer second)))

(s/def :input.completion-item/kind
  (s/and integer?
         enum-val->completion-item-kind
         (s/conformer enum-val->completion-item-kind)))

(s/def :input.completion-item/insert-text-format
  (s/and integer?
         enum-val->insert-text-format
         (s/conformer enum-val->insert-text-format)))

(s/def ::input.completion-item
  (s/keys :opt-un [:input.completion-item/kind
                   :input.completion-item/insert-text-format]))

(s/def ::version (s/and integer? (s/conformer int)))
(s/def ::uri string?)

(s/def ::edits (s/coll-of ::text-edit))
(s/def ::text-edits (s/coll-of ::text-edit))
(s/def ::edits-or-error
  (s/and (s/or :error ::response-error
               :edits ::edits)
         (s/conformer second)))

(s/def ::text-document (s/keys :req-un [::version ::uri]))
(s/def ::text-document-edit (s/keys :req-un [::text-document ::edits]))
(s/def ::changes (s/coll-of (s/tuple string? ::edits) :kind map?))

(s/def :create-file/options (s/keys :opt-un [::overwrite ::ignore-if-exists]))

(s/def :create-file/kind (s/and string?
                                #(= % "create")))
(s/def ::create-file (s/keys :req-un [:create-file/kind ::uri]
                             :opt-un [:create-file/options]))
(s/def :rename-file/kind (s/and string?
                                #(= % "rename")))
(s/def :rename-file/old-uri ::uri)
(s/def :rename-file/new-uri ::uri)

(s/def ::rename-file (s/keys :req-un [:rename-file/kind :rename-file/old-uri :rename-file/new-uri]))

(s/def ::document-changes-entry (s/or :create-file ::create-file
                                      :rename-file ::rename-file
                                      :text-document-edit ::text-document-edit))
(s/def ::document-changes (s/and (s/coll-of ::document-changes-entry)
                                 (s/conformer #(map second %))))

(s/def ::workspace-edit
  (s/keys :opt-un [::document-changes ::changes]))

(s/def ::workspace-edit-or-error
  (s/and (s/or :error ::response-error
               ;; Why do we have two names for the same right-hand side?
               ;; Wouldn't `:changes` always match first, meaning we'd never see `:document-changes`?
               ;; And isn't the left-hand side essentially unused?
               :changes ::workspace-edit
               :document-changes ::workspace-edit)
         (s/conformer second)))

(s/def :workspace-edit-params/edit ::workspace-edit-or-error)

(s/def ::workspace-edit-params
  (s/keys :req-un [:workspace-edit-params/edit]))

(s/def ::location (s/keys :req-un [::uri ::range]))
(s/def ::location-or-error
  (s/and (s/or :error ::response-error
               :location ::location)
         (s/conformer second)))
(s/def ::locations (s/coll-of ::location))
(s/def ::locations-or-error
  (s/and (s/or :error ::response-error
               :locations ::locations)
         (s/conformer second)))

(s/def :signature-help/documentation ::documentation)

(s/def :signature-help/parameter (s/keys :req-un [::label]
                                         :opt-un [:signature-help/documentation]))

(s/def :signature-help/parameters (s/coll-of :signature-help/parameter))

(s/def :signature-help/signature-information (s/keys :req-un [::label]
                                                     :opt-un [:signature-help/documentation
                                                              :signature-help/parameters
                                                              :signature-help/active-parameter]))

(s/def :signature-help/signatures (s/coll-of :signature-help/signature-information))

(s/def ::signature-help (s/keys :req-un [:signature-help/signatures]
                                :opt-un [:signature-help/active-signature
                                         :signature-help/active-parameter]))
(s/def ::signature-help-or-error
  (s/and (s/or :error ::response-error
               :signature-help ::signature-help)
         (s/conformer second)))

(def symbol-kind-enum
  {:file 1 :module 2 :namespace 3 :package 4 :class 5 :method 6 :property 7 :field 8 :constructor 9
   :enum 10 :interface 11 :function 12 :variable 13 :constant 14 :string 15 :number 16 :boolean 17
   :array 18 :object 19 :key 20 :null 21 :enum-member 22 :struct 23 :event 24 :operator 25
   :type-parameter 26})

(s/def :symbol/kind (s/and keyword?
                           symbol-kind-enum
                           (s/conformer symbol-kind-enum)))

(s/def :document-symbol/selection-range ::range)

(s/def :document-symbol/detail string?)

(s/def ::document-symbol (s/keys :req-un [::name
                                          :symbol/kind
                                          ::range
                                          :document-symbol/selection-range]
                                 :opt-un [:document-symbol/detail :document-symbol/children]))

(s/def :document-symbol/children (s/coll-of ::document-symbol))

(s/def ::document-symbols (s/coll-of ::document-symbol))
(s/def ::document-symbols-or-error
  (s/and (s/or :error ::response-error
               :document-symbols ::document-symbols)
         (s/conformer second)))

(s/def ::document-highlight (s/keys :req-un [::range]))

(s/def ::document-highlights (s/coll-of ::document-highlight))
(s/def ::document-highlights-or-error
  (s/and (s/or :error ::response-error
               :document-highlights ::document-highlights)
         (s/conformer second)))

(s/def ::workspace-symbol (s/keys :req-un [::name :symbol/kind ::location]))
(s/def ::workspace-symbol-or-error
  (s/and (s/or :error ::response-error
               :workspace-symbol ::workspace-symbol)
         (s/conformer second)))

(s/def ::workspace-symbols (s/coll-of ::workspace-symbol))
(s/def ::workspace-symbols-or-error
  (s/and (s/or :error ::response-error
               :workspace-symbols ::workspace-symbols)
         (s/conformer second)))

(s/def ::severity integer?)

(s/def ::code (s/conformer name))

(s/def ::diagnostic (s/keys :req-un [::range ::message]
                            :opt-un [::severity ::code ::tag ::source ::message]))
(s/def ::diagnostics (s/coll-of ::diagnostic))
(s/def ::publish-diagnostics-params (s/keys :req-un [::uri ::diagnostics]))

(s/def ::marked-string (s/and (s/or :string string?
                                    :marked-string (s/keys :req-un [::language ::value]))
                              (s/conformer second)))

(s/def :markup/kind #{"plaintext" "markdown"})
(s/def :markup/value string?)
(s/def ::markup-content (s/keys :req-un [:markup/kind :markup/value]))

(s/def ::contents (s/and (s/or :marked-strings (s/coll-of ::marked-string)
                               :markup-content ::markup-content)
                         (s/conformer second)))

(s/def ::hover (s/keys :req-un [::contents]
                       :opt-un [::range]))
(s/def ::hover-or-error
  (s/and (s/or :error ::response-error
               :hover ::hover)
         (s/conformer second)))

(s/def :command/title string?)
(s/def :command/command string?)
(s/def :command/arguments (s/coll-of any?))

(s/def ::command (s/keys :req-un [:command/title :command/command]
                         :opt-un [:command/arguments]))

(s/def ::any-or-error
  (s/and (s/or :error ::response-error
               :any identity)
         (s/conformer second)))

(def show-message-type-enum
  {:error 1
   :warning 2
   :info 3
   :log 4})

(s/def :show-message/type (s/and keyword?
                                 show-message-type-enum
                                 (s/conformer show-message-type-enum)))

(s/def :show-message/message string?)

(s/def ::show-message (s/keys :req-un [:show-message/type
                                       :show-message/message]))

(s/def :show-message-request-action/title string?)

(s/def :show-message-request/action (s/keys :req-un [:show-message-request-action/title]))

(s/def :show-message-request/actions (s/coll-of :show-message-request/action))

(s/def ::show-message-request (s/keys :req-un [:show-message/type
                                               :show-message/message]
                                      :opt-un [:show-message-request/actions]))

(s/def ::show-document-request
  (s/and (s/keys :req-un [::uri ::range]
                 :opt-un [::take-focus])
         (s/conformer (fn [element]
                        (set/rename-keys element {:range :selection})))))

(s/def :code-action/title string?)

(s/def :code-action/edit ::workspace-edit-or-error)

(def code-action-kind
  {:quick-fix "quickfix"
   :refactor "refactor"
   :refactor-extract "refactor.extract"
   :refactor-inline "refactor.inline"
   :refactor-rewrite "refactor.rewrite"
   :source "source"
   :source-organize-imports "source.organizeImports"})

(s/def :code-action/preferred boolean?)

(s/def :code-action/kind (s/and (s/or :keyword (s/and keyword?
                                                      code-action-kind
                                                      (s/conformer code-action-kind))
                                      :string string?)
                                (s/conformer second)))

(s/def ::code-action (s/keys :req-un [:code-action/title]
                             :opt-un [:code-action/kind
                                      ::diagnostics
                                      :code-action/edit
                                      ::command
                                      :code-action/preferred
                                      ::data]))
(s/def ::code-action-or-error
  (s/and (s/or :error ::response-error
               :code-action ::code-action)
         (s/conformer second)))

(s/def ::code-actions (s/coll-of ::code-action))
(s/def ::code-actions-or-error
  (s/and (s/or :error ::response-error
               :code-actions ::code-actions)
         (s/conformer second)))

(s/def ::code-lens (s/keys :req-un [::range]
                           :opt-un [::command ::data]))
(s/def ::code-lens-or-error
  (s/and (s/or :error ::response-error
               :code-lens ::code-lens)
         (s/conformer second)))

(s/def ::code-lenses (s/coll-of ::code-lens))
(s/def ::code-lenses-or-error
  (s/and (s/or :error ::response-error
               :code-lenses ::code-lenses)
         (s/conformer second)))

(s/def ::semantic-tokens (s/keys :req-un [::data]
                                 :opt-un [::result-id]))
(s/def ::semantic-tokens-or-error
  (s/and (s/or :error ::response-error
               :semantic-tokens ::semantic-tokens)
         (s/conformer second)))

(s/def ::call-hierarchy-item (s/keys :req-un [::name
                                              :symbol/kind
                                              ::uri
                                              ::range
                                              ::selection-range]
                                     :opt-un [::tags ::detail ::data]))

(s/def ::call-hierarchy-items (s/coll-of ::call-hierarchy-item))
(s/def ::call-hierarchy-items-or-error
  (s/and (s/or :error ::response-error
               :call-hierarchy-items ::call-hierarchy-items)
         (s/conformer second)))

(s/def :call-hierarchy/from-ranges (s/coll-of ::range))
(s/def :call-hierarchy/from ::call-hierarchy-item)
(s/def :call-hierarchy/to ::call-hierarchy-item)

(s/def ::call-hierarchy-incoming-call (s/keys :req-un [:call-hierarchy/from :call-hierarchy/from-ranges]))

(s/def ::call-hierarchy-outgoing-call (s/keys :req-un [:call-hierarchy/to :call-hierarchy/from-ranges]))

(s/def ::call-hierarchy-incoming-calls (s/coll-of ::call-hierarchy-incoming-call))
(s/def ::call-hierarchy-incoming-calls-or-error
  (s/and (s/or :error ::response-error
               :call-hierarchy-incoming-calls ::call-hierarchy-incoming-calls)
         (s/conformer second)))
(s/def ::call-hierarchy-outgoing-calls (s/coll-of ::call-hierarchy-outgoing-call))
(s/def ::call-hierarchy-outgoing-calls-or-error
  (s/and (s/or :error ::response-error
               :call-hierarchy-outgoing-calls ::call-hierarchy-outgoing-calls)
         (s/conformer second)))

(s/def :linked-editing-range/ranges (s/coll-of ::range))

(s/def ::linked-editing-ranges
  (s/keys :req-un [:linked-editing-range/ranges]
          :opt-un [::word-pattern]))

(s/def ::linked-editing-ranges-or-error
  (s/and (s/or :error ::response-error
               :ranges ::linked-editing-ranges)
         (s/conformer second)))

(s/def :folding-range/kind keyword?)

(s/def ::folding-range
  (s/keys :req-un [::start-line ::end-line]
          :opt-un [::start-character ::end-character :folding-range/kind ::collapsed-text]))

(s/def ::folding-ranges (s/coll-of ::folding-range))

(s/def ::folding-ranges-or-error
  (s/and (s/or :error ::response-error
               :ranges ::folding-ranges)
         (s/conformer second)))


(s/def ::selection-range-response
  (s/keys :req-un [::range]
          :opt-un [:selection-range/parent]))

(s/def :selection-range/parent ::selection-range-response)
(s/def ::selection-ranges-response (s/coll-of ::selection-range-response))

(def inlay-hint-kind-enum {:type 1 :parameter 2})

(s/def :inlay-hint/kind (s/and keyword?
                               inlay-hint-kind-enum
                               (s/conformer inlay-hint-kind-enum)))

(s/def ::inlay-hint
  (s/keys :req-un [::position ::label]
          :opt-un [:inlay-hint/kind ::text-edits ::tooltip ::padding-left ::padding-right ::data]))
(s/def ::inlay-hints (s/coll-of ::inlay-hint))

(s/def :server-capabilities/signature-help-provider
  (s/conformer #(cond (vector? %) {:trigger-characters %}
                      (map? %) %
                      :else {:trigger-characters %})))
(s/def :server-capabilities/code-action-provider
  (s/conformer #(when (vector? %) {:code-action-kinds %})))
(s/def :server-capabilities/execute-command-provider
  (s/conformer #(when (vector? %) {:commands %})))
(s/def :server-capabilities/code-lens-provider
  (s/conformer (fn [element] {:resolve-provider element})))
(s/def :server-capabilities/rename-provider
  (s/conformer (fn [element] {:prepare-provider element})))
(s/def :server-capabilities/semantic-tokens-provider
  (s/conformer #(when (and (:token-types %)
                           (:token-modifiers %))
                  {:legend {:token-types (:token-types %)
                            :token-modifiers (:token-modifiers %)}
                   :range (:range %)
                   :full (boolean (get % :full false))})))
(def text-docyment-sync-kind
  {:none 0
   :full 1
   :incremental 2})
(s/def :server-capabilities/text-document-sync
  (s/conformer (fn [element]
                 {:open-close true
                  :change (or (get text-docyment-sync-kind element)
                              (get text-docyment-sync-kind :full))
                  :save {:include-text true}})))

(s/def ::server-capabilities
  (s/keys :opt-un [:server-capabilities/code-action-provider
                   :server-capabilities/code-lens-provider
                   :server-capabilities/execute-command-provider
                   :server-capabilities/rename-provider
                   :server-capabilities/semantic-tokens-provider
                   :server-capabilities/signature-help-provider
                   :server-capabilities/text-document-sync]))

(s/def :json-rpc.message/jsonrpc #{"2.0"})
(s/def :json-rpc.message/method string?)
(s/def :json-rpc.message/id (s/and (s/or :s string? :i nat-int? :n nil?)
                                   (s/conformer second)))

(s/def ::json-rpc.request
  (s/keys :req-un [:json-rpc.message/jsonrpc
                   :json-rpc.message/id
                   :json-rpc.message/method]
          :opt-un [:json-rpc.message/params]))
(s/def ::json-rpc.notification
  (s/keys :req-un [:json-rpc.message/jsonrpc
                   :json-rpc.message/method]
          :opt-un [:json-rpc.message/params]))
(s/def ::json-rpc.response.result
  (s/keys :req-un [:json-rpc.message/jsonrpc
                   :json-rpc.message/id
                   :json-rpc.message/result]))
(s/def ::json-rpc.response.error
  (s/keys :req-un [:json-rpc.message/jsonrpc
                   :json-rpc.message/id
                   ::error]))

(s/def ::json-rpc.input
  (s/or :request ::json-rpc.request
        :notification ::json-rpc.notification
        :response.result ::json-rpc.response.result
        :response.error ::json-rpc.response.error))

(defn input-message-type [message]
  (if (identical? :parse-error message)
    :parse-error
    (let [conformed-message (s/conform ::json-rpc.input message)]
      (if (identical? ::s/invalid conformed-message)
        :invalid-request
        (first conformed-message)))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn conform-or-log [log spec value]
  (when value
    (try
      (let [result (s/conform spec value)]
        (if (identical? ::s/invalid result)
          (log "Conformation error" (s/explain-data spec value))
          result))
      (catch Exception ex
        (log ex "Conformation exception" spec value)))))
