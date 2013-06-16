(ns web-crawler.core
  (:require [net.cgrand.enlive-html :as enlive])
  (:use [clojure.string :only (lower-case)]
        [clojure.java.io])
  (:import [java.net URL MalformedURLException]
           [java.util.concurrent BlockingQueue LinkedBlockingQueue]))

(defn- links-from
  [base-url html]
  ; 删除空的链接， link为html内容中的a标记
  (remove nil? (for [link (enlive/select html [:a])]
                 ; 如果此link有attrs属性和href属性，href即是网址
                 (when-let [href (-> link :attrs :href)]
                   (try
                     ; 生成一个URL对象
                     (URL. base-url href)
                     ; 跳过错误的网址URL
                     (catch MalformedURLException e))))))

(defn- words-from
  [html]
  ; chunks就是未经过滤的字串序列
  (let [chunks (-> html
                   (enlive/at [:script] nil)
                   ; 选择body的所有的text-node
                   (enlive/select [:body enlive/text-node]))]
    (->> chunks
         ; 对每个字串分解出单词，使用正则表达式re-seq来分割单词
         ; mapcat 对每一字串分解出来的单词序列合并，返回一个单词序列
         (mapcat (partial re-seq #"\w+"))
         ; re-matches匹配时返回匹配的数据，否则返回nil
         ; 这样就可以用remove删除和数字匹配的字串，得到只有英文单词的序列
         (remove (partial re-matches #"\d+"))
         (map lower-case))))


(def url-queue (LinkedBlockingQueue.))
(def crawled-urls (atom #{}))
(def word-freqs (atom {}))

(declare get-url)
; 每个agent都有::t字段，表示下一步将要执行的动作
(def agents (set (repeatedly 25 #(agent {::t #'get-url :queue url-queue}))))

(declare run process handle-results)
;; ^::blocking是函数的meta data，表示该函数式阻塞式的
;; 对使用者来说有用
(defn ^::blocking get-url
  [{:keys [^BlockingQueue queue] :as state}]
  ;; 从url-queue中获取一个链接，一直等待状态
  (let [url (as-url (.take queue))]
    (try
      ;; 如果该网页已经抓去过，则原封不动返回
      (if (@crawled-urls url)
        state
        ;; 否则抓取该链接的网页，并返回包含内容和下一步动作的map
        {:url url
         :content (slurp url)
         ::t #'process})
      ;; 出错时原封不动返回
      (catch Exception e
        state)
      ;; 这里保证有限状态机不会停止，最后启动动作，继续执行下一步动作
      ;; *agent*表示当前的agent
      (finally (run *agent*)))))

(defn process
  [{:keys [url content]}]
  (try
    ;; 从字串转换成StringReader对象
    (let [html (enlive/html-resource (java.io.StringReader. content))]
      {::t #'handle-results
       :url url
       ;; links包含了网页中所有的链接
       :links (links-from url html)
       ;; 从单词序列中建立以单词为key，词频为value的map
       :words (reduce (fn [m word]
                        ;; fnil表示如果key刚添加的话则从1开始计数
                        (update-in m [word] (fnil inc 0)))
                      {}
                      (words-from html))})
    ;; 继续执行下一步动作 
    (finally (run *agent*))))


(defn ^::blocking handle-results
  [{:keys [url links words]}]
  (try
    ;; 添加已经抓去过的网页集
    (swap! crawled-urls conj url)
    ;; 往队列中添加所有的链接，其他等待的agent就可以获取一个链接
    ;; 其他的动作就可以王下走了
    (doseq [url links]
      (.put url-queue url))
    ;; 更新单词和词频，词频用+来合并
    (swap! word-freqs (partial merge-with +) words)
    
    ;; 最终返回原始的状态
    {::t #'get-url :queue url-queue}

    ;;继续执行下一步动作
    (finally (run *agent*))))

;; 检查agent是否被停掉
(defn paused? [agent] (::paused (meta agent)))

(defn run
  ([] (doseq [a agents] (run a)))
  ([a]
     (when (agents a)
       (send a (fn [{transition ::t :as state}]
                 ;; 检查当前agent是否处于停止状态
                 (when-not (paused? *agent*)
                   ;; 根据执行动作的::blocking属性来调用send-off还是send
                   (let [dispatch-fn (if (-> transition meta ::blocking)
                                       send-off
                                       send)]
                     ;; 在threading pool线程执行的send或send-off操作
                     (dispatch-fn *agent* transition)))
                 state)))))

(defn pause
  ([] (doseq [a agents] (pause a)))
  ([a] (alter-meta! a assoc ::paused true)))

(defn restart
  ([] (doseq [a agents] (restart a)))
  ([a]
     (alter-meta! a dissoc ::paused)
     (run a)))

(defn test-crawler
  [agent-count starting-url]
  ;; 建立所有的agent，用来测试用的
  (def agents (set (repeatedly agent-count 
                               #(agent {::t #'get-url :queue url-queue}))))
  ;; 清除url队列
  (.clear url-queue)

  ;; 清空状态
  (swap! crawled-urls empty)
  (swap! word-freqs empty)

  (.add url-queue starting-url)
  (run)
  (Thread/sleep 60000)
  (pause)
  [(count @crawled-urls) (count url-queue)])
