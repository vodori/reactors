(defproject reactors "0.1.1-SNAPSHOT"
  :description
  "A clojure library for maintaining state, incorporating events
   from multiple sources and broadcasting an interpretation of the
   change in state to any observers."

  :url
  "https://github.com/vodori/reactors"

  :license
  {:name "MIT License" :url "http://opensource.org/licenses/MIT" :year 2018 :key "mit"}

  :scm
  {:name "git" :url "https://github.com/vodori/reactors"}

  :dependencies
  [[org.clojure/clojure "1.10.0-alpha8"]
   [org.clojure/core.async "0.4.474"]
   [com.taoensso/timbre "4.10.0"]]

  :pom-addition
  [:developers
   [:developer
    [:name "Paul Rutledge"]
    [:url "https://github.com/rutledgepaulv"]
    [:email "paul.rutledge@vodori.com"]
    [:timezone "-5"]]]

  :deploy-repositories
  {"releases"  {:url "https://oss.sonatype.org/service/local/staging/deploy/maven2/" :creds :gpg}
   "snapshots" {:url "https://oss.sonatype.org/content/repositories/snapshots/" :creds :gpg}})
