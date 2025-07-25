/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest.utils.kafkaUtils;

import io.skodjob.testframe.enums.LogLevel;
import io.skodjob.testframe.resources.KubeResourceManager;
import io.strimzi.api.kafka.model.common.Condition;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalance;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceAnnotation;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceState;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalanceStatus;
import io.strimzi.operator.common.Annotations;
import io.strimzi.systemtest.TestConstants;
import io.strimzi.systemtest.resources.ResourceConditions;
import io.strimzi.systemtest.resources.ResourceOperation;
import io.strimzi.test.TestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static io.strimzi.systemtest.resources.CrdClients.kafkaRebalanceClient;

public class KafkaRebalanceUtils {

    private static final Logger LOGGER = LogManager.getLogger(KafkaRebalanceUtils.class);

    private KafkaRebalanceUtils() {}

    /**
     * Replaces KafkaRebalance in specific Namespace based on the edited resource from {@link Consumer}.
     *
     * @param namespaceName     name of the Namespace where the resource should be replaced.
     * @param resourceName      name of the KafkaRebalance's name.
     * @param editor            editor containing all the changes that should be done to the resource.
     */
    public static void replace(String namespaceName, String resourceName, Consumer<KafkaRebalance> editor) {
        KafkaRebalance kafkaRebalance = kafkaRebalanceClient().inNamespace(namespaceName).withName(resourceName).get();
        KubeResourceManager.get().replaceResourceWithRetries(kafkaRebalance, editor);
    }

    public static Condition rebalanceStateCondition(String namespaceName, String resourceName) {

        List<Condition> statusConditions = kafkaRebalanceClient().inNamespace(namespaceName)
                .withName(resourceName).get().getStatus().getConditions().stream()
                .filter(condition -> condition.getType() != null)
                .filter(condition -> Arrays.stream(KafkaRebalanceState.values()).anyMatch(stateValue -> stateValue.toString().equals(condition.getType())))
                .toList();

        if (statusConditions.size() == 1) {
            return statusConditions.get(0);
        } else if (statusConditions.size() > 1) {
            LOGGER.warn("Multiple KafkaRebalance State Conditions were present in the KafkaRebalance status");
            throw new RuntimeException("Multiple KafkaRebalance State Conditions were present in the KafkaRebalance status");
        } else {
            LOGGER.warn("No KafkaRebalance State Conditions were present in the KafkaRebalance status");
            throw new RuntimeException("No KafkaRebalance State Conditions were present in the KafkaRebalance status");
        }
    }

    public static void waitForKafkaRebalanceCustomResourceState(String namespaceName, String resourceName, KafkaRebalanceState state) {
        KafkaRebalance kafkaRebalance = kafkaRebalanceClient().inNamespace(namespaceName).withName(resourceName).get();
        KubeResourceManager.get().waitResourceCondition(kafkaRebalance, ResourceConditions.resourceHasDesiredState(state), ResourceOperation.getTimeoutForKafkaRebalanceState(state));
    }

    public static String annotateKafkaRebalanceResource(String namespaceName, String resourceName, KafkaRebalanceAnnotation annotation) {
        LOGGER.info("Annotating KafkaRebalance: {} with annotation: {}", resourceName, annotation.toString());
        return KubeResourceManager.get().kubeCmdClient().inNamespace(namespaceName)
            .exec(true, LogLevel.DEBUG, true, "annotate", "kafkarebalance", resourceName, Annotations.ANNO_STRIMZI_IO_REBALANCE + "=" + annotation)
            .out()
            .trim();
    }

    public static void doRebalancingProcessWithAutoApproval(String namespaceName, String rebalanceName) {
        doRebalancingProcess(namespaceName, rebalanceName, true);
    }

    public static void doRebalancingProcess(String namespaceName, String rebalanceName) {
        doRebalancingProcess(namespaceName, rebalanceName, false);
    }

    public static void doRebalancingProcess(String namespaceName, String rebalanceName, boolean autoApproval) {
        LOGGER.info(String.join("", Collections.nCopies(76, "=")));
        LOGGER.info(kafkaRebalanceClient().inNamespace(namespaceName).withName(rebalanceName).get().getStatus().getConditions().get(0).getType());
        LOGGER.info(String.join("", Collections.nCopies(76, "=")));

        if (!autoApproval) {
            // it can sometimes happen that KafkaRebalance is already in the ProposalReady state -> race condition prevention
            if (!rebalanceStateCondition(namespaceName, rebalanceName).getType().equals(KafkaRebalanceState.ProposalReady.name())) {
                LOGGER.info("Verifying that KafkaRebalance resource is in {} state", KafkaRebalanceState.ProposalReady);

                waitForKafkaRebalanceCustomResourceState(namespaceName, rebalanceName, KafkaRebalanceState.ProposalReady);
            }

            LOGGER.info(String.join("", Collections.nCopies(76, "=")));
            LOGGER.info(kafkaRebalanceClient().inNamespace(namespaceName).withName(rebalanceName).get().getStatus().getConditions().get(0).getType());
            LOGGER.info(String.join("", Collections.nCopies(76, "=")));

            // using automatic-approval annotation
            final KafkaRebalance kr = kafkaRebalanceClient().inNamespace(namespaceName).withName(rebalanceName).get();
            if (kr.getMetadata().getAnnotations().containsKey(Annotations.ANNO_STRIMZI_IO_REBALANCE_AUTOAPPROVAL) &&
                kr.getMetadata().getAnnotations().get(Annotations.ANNO_STRIMZI_IO_REBALANCE_AUTOAPPROVAL).equals("true")) {
                LOGGER.info("Triggering the rebalance automatically (because Annotations.ANNO_STRIMZI_IO_REBALANCE_AUTOAPPROVAL is set to true) " +
                    "without an annotation {} of KafkaRebalance resource", "strimzi.io/rebalance=approve");
            } else {
                LOGGER.info("Triggering the rebalance with annotation {} of KafkaRebalance resource", "strimzi.io/rebalance=approve");

                String response = annotateKafkaRebalanceResource(namespaceName, rebalanceName, KafkaRebalanceAnnotation.approve);

                LOGGER.info("Response from the annotation process {}", response);
            }
        }

        LOGGER.info("Verifying that KafkaRebalance is in the {} state", KafkaRebalanceState.Ready);

        waitForKafkaRebalanceCustomResourceState(namespaceName, rebalanceName, KafkaRebalanceState.Ready);
    }

    public static void waitForRebalanceStatusStability(String namespaceName, String resourceName) {
        int[] stableCounter = {0};

        KafkaRebalanceStatus oldStatus = kafkaRebalanceClient().inNamespace(namespaceName).withName(resourceName).get().getStatus();

        TestUtils.waitFor("KafkaRebalance status to be stable", TestConstants.GLOBAL_POLL_INTERVAL, TestConstants.GLOBAL_STATUS_TIMEOUT, () -> {
            if (kafkaRebalanceClient().inNamespace(namespaceName).withName(resourceName).get().getStatus().equals(oldStatus)) {
                stableCounter[0]++;
                if (stableCounter[0] == TestConstants.GLOBAL_STABILITY_OFFSET_TIME) {
                    LOGGER.info("KafkaRebalance status is stable for: {} poll intervals", stableCounter[0]);
                    return true;
                }
            } else {
                LOGGER.info("KafkaRebalance status is not stable. Going to set the counter to zero");
                stableCounter[0] = 0;
                return false;
            }
            LOGGER.info("KafkaRebalance status gonna be stable in {} polls", TestConstants.GLOBAL_STABILITY_OFFSET_TIME - stableCounter[0]);
            return false;
        });
    }

    public static void waitForKafkaRebalanceIsPresent(final String namespaceName, final String kafkaRebalanceName) {
        TestUtils.waitFor("KafkaRebalance is present", TestConstants.GLOBAL_POLL_INTERVAL, TestConstants.GLOBAL_STATUS_TIMEOUT, () -> kafkaRebalanceClient().inNamespace(namespaceName).withName(kafkaRebalanceName).get() != null);
    }

    public static void waitForKafkaRebalanceIsDeleted(final String namespaceName, final String kafkaRebalanceName) {
        TestUtils.waitFor("KafkaRebalance is deleted", TestConstants.GLOBAL_POLL_INTERVAL, TestConstants.GLOBAL_STATUS_TIMEOUT, () -> kafkaRebalanceClient().inNamespace(namespaceName).withName(kafkaRebalanceName).get() == null);
    }
}
