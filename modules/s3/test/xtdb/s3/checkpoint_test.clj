(ns xtdb.s3.checkpoint-test
  (:require [clojure.test :as t]
            [xtdb.fixtures.checkpoint-store :as fix.cp-store]
            [xtdb.s3-test :as s3t]
            [xtdb.s3.checkpoint :as s3c]
            [xtdb.s3.checkpoint-transfer-manager :as s3ctm]
            [xtdb.system :as sys])
  (:import xtdb.s3.S3Configurator
           java.util.UUID
           software.amazon.awssdk.regions.Region
           software.amazon.awssdk.services.s3.S3AsyncClient))

(t/use-fixtures :once s3t/with-s3-client)

(t/deftest test-checkpoint-store
  (with-open [sys (-> (sys/prep-system {:store {:xtdb/module `s3c/->cp-store
                                                :configurator `s3t/->configurator
                                                :bucket s3t/test-s3-bucket
                                                :prefix (str "s3-cp-" (UUID/randomUUID))}})
                      (sys/start-system))]
    (fix.cp-store/test-checkpoint-store (:store sys))))
