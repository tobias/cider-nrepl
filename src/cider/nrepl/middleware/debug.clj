(ns cider.nrepl.middleware.debug
  "Expression-based debugger for clojure code"
  {:author "Artur Malabarba"}
  (:require [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
            [cider.nrepl.middleware.stacktrace :refer [analyze-causes]]
            [cider.nrepl.middleware.util.instrument :refer [instrument]])
  (:import [clojure.lang Compiler$LocalBinding]))

;;;; # The Debugger
;;; The debugger is divided into two parts, intrument.clj and
;;; debug.clj.
;;;
;;; - instrument.clj (which see), found in the util/ subdir, is
;;;   responsible for navigating a code data structure and
;;;   instrumenting all sexps of interest.
;;;
;;; - debug.clj is the debugger per se. It doesn't actually "look" at
;;;   the code it's being run on. It simply implements the breakpoint
;;;   logic as well as a number of functions to interact with the user
;;;   according to breakpoints placed by instrument.clj.
;;;
;;;   After the repl is started, before the debugger can be used, a
;;;   message must be sent by the client with the "init-debugger" op
;;;   (only one of these is necessary througout a session).
;;;   Afterwards, code can be instrumented by calling
;;;   `instrument-and-eval` on it (through the regular "eval" op).
;;;
;;;   Finally, when a breakpoint is reached due to running
;;;   instrumented code, an `need-debug-input` message is sent to the
;;;   client in response to the message used during initialization.
;;;   Execution of the code will halt until this message is replied.
;;;   It may specify a :prompt, it will specify an :input-type and a
;;;   :key, and it expects an :input key in the reply. The :key must
;;;   be contained in the reply, and :input-type may be:
;;;   - a vector of keywords, in which case one must be returned (but
;;;     note that the repl may convert these to non-keyword strings);
;;;   - the keyword :expression, in which case a single sexp must be
;;;     returned (as a string).

;;;; ## Internal breakpoint logic
;;; Variables and functions used for navigating between breakpoints.
(def ^:dynamic *skip-breaks*
  "Boolean or vector to determine whether to skip a breakpoint.
  Don't set or examine this directly, it is bound in the session
  binding map, use `skip-breaks!` and `skip-breaks?` instead.
  Its value is discarded at the end each eval session."
  nil)

(defn skip-breaks?
  "True if the breakpoint at coordinates should be skipped.
  If *skip-breaks* is true, return true.
  If *skip-breaks* is a vector of integers, return true if coordinates
  are deeper than this vector."
  [coordinates]
  (when-let [sb (@(:session *msg*) #'*skip-breaks*)]
    (or
     ;; From :continue, skip everything.
     (true? sb)
     ;; From :out, skip some breaks.
     (let [parent (take (count sb) coordinates)]
       (and (= sb parent)
            (> (count coordinates) (count parent)))))))

(defn- skip-breaks!
  "Set the value of *skip-breaks* in the session binding map."
  [bool-or-vec]
  (swap! (:session *msg*) assoc #'*skip-breaks* bool-or-vec))

(defn- abort!
  "Stop current eval thread.
  This does not quit the repl, it only interrupts an eval operation."
  []
  (.stop (:thread (meta (:session *msg*)))))

;;; Politely borrowed from clj-debugger.
(defn- sanitize-env
  "Turn a macro's &env into a map usable for binding."
  [env]
  (into {} (for [[sym bind] env
                 :when (instance? Compiler$LocalBinding bind)]
             [`(quote ~sym) (.sym bind)])))

;;;; ## Getting user input
;;; `wrap-debug` receives an initial message from the client, stores
;;; it in `debugger-message`, and `breakpoint` answers it when asking
;;; for input.
(def debugger-message
  "The message being used to communicate with the client.
  Stored by the \"init-debugger\" op, and used by `read-debug` to ask
  for debug input through the :need-debug-input status."
  (atom nil))

(def debugger-input
  "Map atom holding all unprocessed debug inputs.
  This is where the \"debug\" op stores replies received for debug
  input requests. `read-debug` will halt until it finds its input in
  this map (identified by a key), and will `dissoc` it afterwards."
  (atom {}))

(defn- read-debug
  "Like `read`, but reply is sent through `debugger-message`.
  type is sent in the message as :input-type."
  [extras type prompt]
  (let [key (str (java.util.UUID/randomUUID))]
    (->> (assoc extras
                :status :need-debug-input
                :key key
                :prompt prompt
                :input-type type)
         (response-for @debugger-message)
         (transport/send (:transport @debugger-message)))
    (while (not (@debugger-input key))
      (java.lang.Thread/sleep 100))
    (let [input (@debugger-input key)]
      (swap! debugger-input dissoc key)
      input)))

(def ^:dynamic *locals*
  "Bound by the `breakpoint` macro to the local &env."
  {})

(defn- eval-with-locals
  "`eval` form wrapped in a let of the *locals* map."
  [form]
  (eval
   `(let ~(vec (mapcat #(list % `(*locals* '~%)) (keys *locals*)))
      ~form)))

(defn- read-debug-eval-expression
  "Read and eval an expression from the client.
  extras is a map to be added to the message, and prompt is added into
  the :prompt key."
  [prompt extras]
  (eval-with-locals (read-debug extras :expression prompt)))

(def commands
  "Vector of defined debug commands."
  [:next :continue :out :inject :eval :quit])

(def commands-prompt
  "Vector of defined debug commands."
  "(n)ext (c)ontinue (o)ut (i)nject (e)val (q)uit")

(defn read-debug-command
  "Read and take action on a debugging command.
  Ask for one of the following debug commands using `read-debug`:

  next: Return value.
  continue: Skip breakpoints for the remainder of this eval session.
  out: Skip breakpoints in the current sexp.
  inject: Evaluate an expression and return it.
  eval: Evaluate an expression, display result, and prompt again.
  quit: Abort current eval session."
  [value extras]
  (case (read-debug extras commands commands-prompt)
    :next     value
    :continue (do (skip-breaks! true) value)
    :out      (do (skip-breaks! (butlast (:coor extras))) value)
    :inject   (read-debug-eval-expression "Expression to inject: " extras)
    :eval     (let [return (read-debug-eval-expression "Expression to evaluate: " extras)]
                (read-debug-command value (assoc extras :debug-value (pr-str return))))
    :quit     (abort!)))

;;; ## High-level functions
(defmacro breakpoint
  "Send value and coordinates to the client through the debug channel.
  Sends a response to the message stored in debugger-message."
  [value extras]
  `(binding [*locals* ~(sanitize-env &env)]
     (let [val# ~value
           ex#  ~extras]
       (if (skip-breaks? (:coor ex#))
         val#
         (read-debug-command val#
                             (assoc ex#
                                    :debug-value (pr-str val#)
                                    :breakfunction nil))))))

(defn instrument-and-eval
  "Instrument form and evaluate the result.
  Call cider.nrepl.middleware.util.instrument."
  [ex form]
  (eval
   (instrument (merge {:coor [], :breakfunction #'breakpoint} ex)
               form)))

;;; ## The middleware definition
(defn wrap-debug [h]
  (fn [{:keys [op input force] :as msg}]
    (case op
      "debug-input"
      (do (swap! debugger-input assoc (:key msg) (read-string input))
          (transport/send (:transport msg) (response-for msg :status :done)))
      "init-debugger"
      (let [stored-message @debugger-message]
        (if (and stored-message (not force))
          (transport/send (:transport msg)
                          (response-for msg :status :done))
          (do (when stored-message
                (transport/send (:transport stored-message)
                                (response-for stored-message :status :done)))
              (reset! debugger-message msg))))
      ;; else
      (h msg))))

(set-descriptor!
 #'wrap-debug
 {:handles
  {"debug-input"
   {:doc "Read client input on debug action."
    :requires {"input" "The user's reply to the input request."}
    :returns {"status" "done"}}
   "init-debugger"
   {:doc "Initialize the debugger so that `breakpoint` works correctly.
This usually does not respond immediately. It sends a response when a
breakpoint is reached or when the message is discarded."
    :requires {"id" "A message id that will be responded to when a breakpoint is reached."}
    :returns {"status" "\"done\" if the message will no longer be used, or \"need-debug-input\" during debugging sessions"}}}})
