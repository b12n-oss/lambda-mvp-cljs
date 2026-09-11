(ns script.aws-lifecycle
  "Generic (no hardcoded profile/account/region) create-or-update /
  invoke / teardown for the lambda-mvp-cljs demo function, driven
  entirely by whatever the caller's aws CLI already has configured
  (AWS_PROFILE/AWS_REGION env vars, or `aws configure`). Deploys to
  AWS's MANAGED nodejs24.x runtime (package-type Zip) -- unlike the
  Jolt/jank siblings, there's no custom Runtime API loop and no
  bootstrap binary; the zip just needs dist/index.js with a `handler`
  export. Invoked via `bb deploy`/`bb invoke`/`bb teardown`, or
  directly: `bb script/aws_lifecycle.clj deploy|invoke|teardown`
  (this project's own compiled handler can't run this file: it needs
  cheshire.core, which shadow-cljs/Node doesn't bundle, same reason
  the Jolt/jank versions of this file need babashka too)."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def function-name (or (System/getenv "LAMBDA_MVP_FUNCTION_NAME") "lambda-mvp-cljs"))
(def role-name (str function-name "-role"))
(def zip-path "dist/lambda.zip")
(def policy-arn "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole")

;; No native binary here (pure JS via Node), so architecture is a free
;; choice, unlike the Jolt/jank siblings where it's read from a built
;; binary's own header. arm64 (Graviton) is the default: cheaper per
;; ms-billed than x86_64 for a function with no native dependencies.
(def lambda-arch (or (System/getenv "LAMBDA_ARCH") "arm64"))

(defn- sh [& args]
  (let [{:keys [exit out err]} (apply p/shell {:out :string :err :string :continue true} args)]
    {:exit exit :out out :err err}))

(defn- die! [& msg]
  (binding [*out* *err*]
    (apply println "lambda-mvp-cljs:" msg))
  (System/exit 1))

(defn- require-aws-identity!
  "Fail fast with a clear message if the aws CLI has no usable
  credentials/region, rather than letting a later call fail obscurely."
  []
  (let [{:keys [exit err]} (sh "aws" "sts" "get-caller-identity" "--output" "json")]
    (when-not (zero? exit)
      (die! "aws CLI has no usable credentials/region."
            "Set AWS_PROFILE/AWS_REGION or run `aws configure`, then retry.\n"
            (str/trim (or err ""))))))

(def ^:private trust-policy
  (json/generate-string
   {:Version "2012-10-17"
    :Statement [{:Effect "Allow"
                 :Principal {:Service "lambda.amazonaws.com"}
                 :Action "sts:AssumeRole"}]}))

(defn- role-exists? []
  (zero? (:exit (sh "aws" "iam" "get-role" "--role-name" role-name))))

(defn- ensure-role! []
  (if (role-exists?)
    (println "lambda-mvp-cljs: role" role-name "already exists")
    (do
      (println "lambda-mvp-cljs: creating role" role-name)
      (let [{:keys [exit err]} (sh "aws" "iam" "create-role"
                                   "--role-name" role-name
                                   "--assume-role-policy-document" trust-policy)]
        (when-not (zero? exit) (die! "create-role failed:" err)))
      ;; IAM role propagation is eventually consistent -- a create-function
      ;; immediately after create-role can fail with "role cannot be assumed".
      (println "lambda-mvp-cljs: waiting 10s for IAM role propagation")
      (Thread/sleep 10000)))
  ;; Always (re-)attach the policy, whether the role is new or pre-existing --
  ;; attach-role-policy is itself idempotent, so this closes the gap where a
  ;; prior run created the role but died before attaching the policy.
  (let [{:keys [exit err]} (sh "aws" "iam" "attach-role-policy"
                               "--role-name" role-name
                               "--policy-arn" policy-arn)]
    (when-not (zero? exit) (die! "attach-role-policy failed:" err))))

(defn- role-arn []
  (-> (sh "aws" "iam" "get-role" "--role-name" role-name
          "--query" "Role.Arn" "--output" "text")
      :out str/trim))

(defn- function-exists? []
  (zero? (:exit (sh "aws" "lambda" "get-function" "--function-name" function-name))))

(defn- ensure-function! []
  (when-not (.exists (java.io.File. zip-path))
    (die! zip-path "not found -- run `bb build` first."))
  (if (function-exists?)
    (do
      (println "lambda-mvp-cljs: updating function code for" function-name (str "(" lambda-arch ")"))
      ;; --architectures on update too: without it, a function created arm64
      ;; keeps arm64 and an x86_64 zip fails at init.
      (let [{:keys [exit err]} (sh "aws" "lambda" "update-function-code"
                                   "--function-name" function-name
                                   "--architectures" lambda-arch
                                   "--zip-file" (str "fileb://" zip-path))]
        (when-not (zero? exit) (die! "update-function-code failed:" err)))
      (let [{:keys [exit err]} (sh "aws" "lambda" "wait" "function-updated" "--function-name" function-name)]
        (when-not (zero? exit) (die! "function did not reach Active state:" err)))
      (let [{:keys [exit err]} (sh "aws" "lambda" "update-function-configuration"
                                   "--function-name" function-name
                                   "--timeout" "15" "--memory-size" "2048")]
        (when-not (zero? exit) (die! "update-function-configuration failed:" err))))
    (do
      (println "lambda-mvp-cljs: creating function" function-name (str "(" lambda-arch ")"))
      (let [{:keys [exit err]} (sh "aws" "lambda" "create-function"
                                   "--function-name" function-name
                                   "--runtime" "nodejs24.x"
                                   "--architectures" lambda-arch
                                   "--handler" "index.handler"
                                   "--zip-file" (str "fileb://" zip-path)
                                   "--role" (role-arn)
                                   "--timeout" "15" "--memory-size" "2048")]
        (when-not (zero? exit) (die! "create-function failed:" err)))))
  (let [{:keys [exit err]} (sh "aws" "lambda" "wait" "function-updated" "--function-name" function-name)]
    (when-not (zero? exit) (die! "function did not reach Active state:" err))))

(defn deploy! []
  (require-aws-identity!)
  (ensure-role!)
  (ensure-function!)
  (println "lambda-mvp-cljs: deployed" function-name "->"
           (-> (sh "aws" "lambda" "get-function" "--function-name" function-name
                   "--query" "Configuration.FunctionArn" "--output" "text")
               :out str/trim)))

(defn invoke! []
  (require-aws-identity!)
  (let [out-file (str (System/getProperty "java.io.tmpdir") "/lambda-mvp-cljs-invoke.json")
        {:keys [exit out err]}
        (sh "aws" "lambda" "invoke"
            "--function-name" function-name
            "--payload" "{}"
            "--cli-binary-format" "raw-in-base64-out"
            "--log-type" "Tail"
            "--query" "LogResult"
            "--output" "text"
            out-file)]
    (when-not (zero? exit) (die! "invoke failed:" err))
    (println "lambda-mvp-cljs: response body:")
    (println (slurp out-file))
    (println "lambda-mvp-cljs: log tail:")
    (println (String. (.decode (java.util.Base64/getDecoder) (str/trim out))))))

(defn teardown! []
  (require-aws-identity!)
  (when (function-exists?)
    (println "lambda-mvp-cljs: deleting function" function-name)
    (let [{:keys [exit err]} (sh "aws" "lambda" "delete-function" "--function-name" function-name)]
      (when-not (zero? exit) (die! "delete-function failed:" err))))
  (when (role-exists?)
    (println "lambda-mvp-cljs: detaching + deleting role" role-name)
    (let [{:keys [exit err]} (sh "aws" "iam" "detach-role-policy" "--role-name" role-name "--policy-arn" policy-arn)]
      (when-not (zero? exit) (die! "detach-role-policy failed:" err)))
    (let [{:keys [exit err]} (sh "aws" "iam" "delete-role" "--role-name" role-name)]
      (when-not (zero? exit) (die! "delete-role failed:" err))))
  (println "lambda-mvp-cljs: teardown complete"))

(defn -main [& args]
  (case (first args)
    "deploy" (deploy!)
    "invoke" (invoke!)
    "teardown" (teardown!)
    (die! "usage: aws_lifecycle.clj deploy|invoke|teardown")))

(apply -main *command-line-args*)
