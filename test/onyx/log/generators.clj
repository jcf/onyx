(ns onyx.log.generators
  (:require [clojure.core.async :refer [chan >!! <!! close!]]
            [onyx.messaging.dummy-messenger :refer [dummy-messenger]]
            [onyx.log.entry :refer [create-log-entry]]
            [onyx.log.commands.common :refer [peer->allocated-job]]
            [onyx.extensions :as extensions]
            [onyx.api :as api]
            [taoensso.timbre :as timbre :refer [info]]
            [clojure.set :refer [intersection]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test :refer :all]))

(def messenger (dummy-messenger {:onyx.peer/try-join-once? false}))

(defn peerless-entry? [log-entry]
  (#{:submit-job :kill-job :gc} (:fn log-entry)))

(defn active-groups [replica entry]
  (cond-> (set (concat (:groups replica)
                       ;; might not need these with the below entries
                       (vals (or (:prepared replica) {}))
                       (vals (or (:accepted replica) {}))))
    ;; joining peer's prepared/accepted may have been removed
    ;; by a leave cluster but the joining peer is still there
    (and (not (peerless-entry? entry))
         (:observer (:args entry)))
    (conj (:observer (:args entry)))
    (and (not (peerless-entry? entry))
         (:id (:args entry)))
    (conj (:id (:args entry)))
    (and (not (peerless-entry? entry))
         (:accepted-joiner (:args entry)))
    (conj (:accepted-joiner (:args entry)))
    (and (not (peerless-entry? entry))
         (:joiner (:args entry)))
    (conj (:joiner (:args entry)))))

(defn active-peers [replica entry]
  (:peers replica))

(defn generate-side-effects
  "Generates additional reactions that may be generated by the process.
  e.g. when the task lifecycle is ready, it signals via :signal-ready"
  [entry old new diff peer-id]
  (cond (#{:kill-job :submit-job :add-virtual-peer
           :prepare-join-cluster :accept-join-cluster :notify-join-cluster
           :leave-cluster :group-leave-cluster :seal-output} (:fn entry))
    (let [old-allocation (peer->allocated-job (:allocations old) peer-id)
          new-allocation (peer->allocated-job (:allocations new) peer-id)]
      (if (and new-allocation (not= old-allocation new-allocation))
        [peer-id [(create-log-entry :signal-ready {:id peer-id})]]))))

(defn iterate-reactions [entry old-replica new-replica diff id]
  (let [reactions
        (extensions/reactions
         entry
         old-replica
         new-replica
         diff
         {:messenger messenger
          :id id
          :opts {:onyx.peer/try-join-once?
                 (:onyx.peer/try-join-once? (:opts messenger) true)}})]
    (when (seq reactions)
      [id reactions])))

(defn collect-reactions [entry old-replica new-replica diff actors]
  (keep (partial iterate-reactions entry old-replica new-replica diff) actors))

(defn collect-side-effects [entry old-replica new-replica diff actors]
  (keep (partial generate-side-effects entry old-replica new-replica diff) actors))

(defn apply-entry [replica entries entry]
  (let [new-replica (extensions/apply-log-entry entry replica)
        diff (extensions/replica-diff entry replica new-replica)
        actors (if (extensions/multiplexed-entry? entry)
                 (into (vec (active-groups replica entry)) (active-peers new-replica entry))
                 (active-peers new-replica entry))
        actor-reactions (collect-reactions entry replica new-replica diff actors)
        side-effects (collect-side-effects entry replica new-replica diff actors)
        new (concat actor-reactions side-effects)
        ; it does not matter that multiple reactions are processed
        ; together because they may be processed interleaved depending on
        ; the choice of peer queue being popped
        unapplied (reduce (fn [new-entries [actor-id reactions]]
                            (update-in 
                             new-entries
                             [actor-id :queue]
                             (fn [queue]
                               (into (vec queue) reactions))))
                          entries
                          new)]
    (vector new-replica diff unapplied {:actors actors
                                        :reactions new})))

(defn apply-peer-queue-entry
  "Applies the next log message in the selected peer's queue.
  Effectively, the next peer that wrote its message to ZK"
  [{:keys [replica message-id entries peer-choices log]} next-group]
  (let [peer-queue (:queue (entries next-group))
        next-entry (first peer-queue)
        new-peer-queue (vec (rest peer-queue))
        new-entries (if (empty? new-peer-queue)
                      (dissoc entries next-group)
                      (assoc-in entries [next-group :queue] new-peer-queue))
        message (assoc next-entry :message-id message-id)
        [new-replica diff updated-entries reactions] (apply-entry replica new-entries message)]
    {:replica new-replica
     :message-id (inc message-id)
     :entries updated-entries
     :log (conj log [message diff reactions])
     :peer-choices (conj peer-choices next-group)}))

(defn queue-select-gen
  "Generator to look into all of the peer's write queues
  and pick an entry to get fake written next"
  [replica-state-gen]
  (gen/bind replica-state-gen
            (fn [state]
              ;; we only play back log messages from peers who have joined
              (let [replica (:replica state)
                    peerless-queues (->> (:entries state)
                                         (filter (fn [[queue-id {:keys [predicate queue]}]]
                                                   (or (peerless-entry? (first queue))
                                                       ((or predicate (constantly true))
                                                        replica
                                                        (first queue)))))
                                         (map key))
                    joined-peers (set (:peers replica))
                    joined-groups (set (:groups replica))
                    selectable-peers (->> (:entries state)
                                          (filter (fn [[peer {:keys [queue]}]]
                                                    (let [entry (first queue)]
                                                      (contains? joined-peers peer))))
                                          (map key)
                                          set)
                    selectable-groups (->> (:entries state)
                                           (filter (fn [[group {:keys [queue]}]]
                                                     (let [entry (first queue)]
                                                       (contains? joined-groups group))))
                                           (map key)
                                           set)
                    selectable-queues (into selectable-groups (into selectable-peers peerless-queues))]
                (if (empty? selectable-queues)
                  (throw (Exception. (str "No playable log messages. State: " state)))
                  (gen/elements selectable-queues))))))

(defn apply-entry-gen
  "Apply an entry from one of the peers log queues
  to a replica generator "
  [replica-state-gen]
  (gen/fmap
    (fn [[state peer-id]]
      (apply-peer-queue-entry state peer-id))
    (gen/tuple replica-state-gen
               (queue-select-gen replica-state-gen))))

(defn apply-entries-gen
  "Recurse over replica generator until entries
  are exhausted. Return the final replica, the log messages
  in the order they were written and the order of the peers
  that got to write"
  [replica-state-gen]
  (gen/bind replica-state-gen
            (fn [state]
              (let [g (gen/return state)]
                (when (> (count (:log state))
                         1000)
                  (throw (Exception. (str "Log entry generator overflow. Likely issue with uncompletable log\n"
                                          (with-out-str (clojure.pprint/pprint state))))))
                (if (empty? (:entries state))
                  g
                  (apply-entries-gen (apply-entry-gen g)))))))

(defn build-join-entry
  ([group-id peer-ids]
   (build-join-entry group-id peer-ids {}))
  ([group-id peer-ids more-args]
   (into
    [{:fn :prepare-join-cluster
      :args (merge {:joiner group-id} more-args)}]
    (map
     (fn [peer-id]
       {:fn :add-virtual-peer
        :args {:id peer-id
               :group-id group-id
               :peer-site (extensions/peer-site messenger)
               :tags []}})
     peer-ids))))

(defn generate-join-queues
  ([group-and-peer-ids]
   (generate-join-queues group-and-peer-ids {}))
  ([group-and-peer-ids more-join-args]
   (zipmap (keys group-and-peer-ids)
           (map (fn [[group-id peer-ids]]
                  {:queue (build-join-entry group-id peer-ids more-join-args)})
                group-and-peer-ids))))

(defn generate-group-and-peer-ids
  ([groups peers]
   (generate-group-and-peer-ids 1 groups
                                1 peers))
  ([groups-low groups-high peers-low peers-high]
   (reduce
    (fn [r g]
      (let [g-id (keyword (str "g" g))
            peers (map
                   #(keyword (str (name g-id) "-p" %))
                   (range peers-low (+ peers-low peers-high)))]
        (assoc r g-id (into #{} peers))))
    {}
    (range groups-low (+ groups-low groups-high)))))
