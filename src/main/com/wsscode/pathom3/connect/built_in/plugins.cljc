(ns com.wsscode.pathom3.connect.built-in.plugins
  (:require
    [com.fulcrologic.guardrails.core :refer [<- => >def >defn >fdef ? |]]
    [com.wsscode.log :as l]
    [com.wsscode.pathom3.connect.indexes :as pci]
    [com.wsscode.pathom3.connect.operation :as pco]
    [com.wsscode.pathom3.connect.planner :as pcp]
    [com.wsscode.pathom3.connect.runner :as pcr]
    [com.wsscode.pathom3.connect.runner.async :as pcra]
    [com.wsscode.pathom3.format.shape-descriptor :as pfsd]
    [com.wsscode.pathom3.interface.async.eql :as p.a.eql]
    [com.wsscode.pathom3.interface.eql :as p.eql]
    [com.wsscode.pathom3.plugin :as p.plugin]
    [com.wsscode.promesa.macros :refer [clet ctry]]))

(defn ^:deprecated attribute-errors-plugin
  "DEPRECATED: attribute errors are now built-in, you can just remove it
  from your setup.

  This plugin makes attributes errors visible in the data."
  []
  {::p.plugin/id
   `attribute-errors-plugin})

(p.plugin/defplugin mutation-resolve-params
  "Remove the run stats from the result meta. Use this in production to avoid sending
  the stats. This is important for performance and security.

  TODO: error story is not complete, still up to decide what to do when params can't
  get fulfilled."
  {::pcr/wrap-mutate
   (fn mutation-resolve-params-external [mutate]
     (fn mutation-resolve-params-internal [env {:keys [key] :as ast}]
       (let [{::pco/keys [params]} (pci/mutation-config env key)]
         (clet [params' (if params
                          (if (::pcra/async-runner? env)
                            (p.a.eql/process env (:params ast) params)
                            (p.eql/process env (:params ast) params))
                          (:params ast))]
           (mutate env (assoc ast :params params'))))))})

(>def ::apply-everywhere? boolean?)

(defn filtered-sequence-items-plugin
  ([] (filtered-sequence-items-plugin {}))
  ([{::keys [apply-everywhere?]}]
   {::p.plugin/id
    `filtered-sequence-items-plugin

    ::pcr/wrap-process-sequence-item
    (fn [map-subquery]
      (fn [env ast m]
        (ctry
          (map-subquery env ast m)
          (catch #?(:clj Throwable :cljs :default) e
            (if (or apply-everywhere?
                    (-> ast :meta ::remove-error-items))
              nil
              (throw e))))))}))

(defn dev-linter
  "This plugin adds linting features to help developers find sources of issues while
  Pathom runs its system.

  Checks done:

  - Verify if all the output that comes out of the resolver is declared in the resolver
    output. This means the user missed some attribute declaration in the resolver output
    and that may cause inconsistent behavior on planning/running."
  []
  {::p.plugin/id
   `dev-linter

   ::pcr/wrap-resolve
   (fn [resolve]
     (fn [env input]
       (clet [{::pco/keys [provides op-name]} (pci/resolver-config env (-> env ::pcp/node ::pco/op-name))
              res              (resolve env input)
              unexpected-shape (pfsd/difference (pfsd/data->shape-descriptor res) provides)]
         (if (seq unexpected-shape)
           (l/warn ::undeclared-output
                   {::pco/op-name      op-name
                    ::pco/provides     provides
                    ::unexpected-shape unexpected-shape}))
         res)))})
