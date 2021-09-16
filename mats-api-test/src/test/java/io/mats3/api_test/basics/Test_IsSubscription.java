package io.mats3.api_test.basics;

import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Test;

import io.mats3.MatsEndpoint;
import io.mats3.api_test.DataTO;
import io.mats3.api_test.StateTO;
import io.mats3.test.junit.Rule_Mats;

public class Test_IsSubscription {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.create();

    @Test
    public void testNonSubscription() {
        MatsEndpoint<Void, StateTO> nonSubscription = MATS.getMatsFactory().terminator(
                "testNonSubscription", StateTO.class, DataTO.class, (context, sto, dto) -> {
                });

        Assert.assertFalse(nonSubscription.getEndpointConfig().isSubscription());
    }

    @Test
    public void testSubscription() {
        MatsEndpoint<Void, StateTO> subscription = MATS.getMatsFactory().subscriptionTerminator(
                "testSubscription", StateTO.class, DataTO.class, (context, sto, dto) -> {
                });

        Assert.assertTrue(subscription.getEndpointConfig().isSubscription());
    }
}
