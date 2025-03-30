/*
 * Copyright 2015-2025 Endre Stølsvik
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mats3.serial.json;

import org.junit.Assert;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mats3.serial.MatsSerializer;
import io.mats3.serial.MatsSerializer.SerializedMatsTrace;
import io.mats3.serial.MatsTrace;
import io.mats3.serial.MatsTrace.Call.CallType;
import io.mats3.serial.MatsTrace.Call.MessagingModel;
import io.mats3.serial.MatsTrace.KeepMatsTrace;
import io.mats3.serial.impl.MatsTraceFieldImpl;

/**
 * @author Endre Stølsvik 2022-02-11 00:32 - http://stolsvik.com/, endre@stolsvik.com
 */
public class TestMatsTraceTimestamps {
    private static final Logger log = LoggerFactory.getLogger(TestMatsTraceTimestamps.class);

    /**
     * Simple init REQUEST -> singleStage REPLY -> receive on Terminator
     * <pre>
     * [Initiator]   - request
     *     [Service] - reply
     * [Terminator]
     * </pre>
     */
    @Test
    public void initRequestReplyToTerminator() {
        MatsSerializerJson ser = MatsSerializerJson.create();

        // :: Perform a REQUEST
        MatsTrace mt = ser.createNewMatsTrace("traceId", "flowId", KeepMatsTrace.FULL, false, false, 0, false);
        // @10 Initialized
        ((MatsTraceFieldImpl) mt).overrideInitiatingTimestamp_ForTests(10);
        mt = mt.addRequestCall("from", "Leaf", MessagingModel.QUEUE, "Terminator", MessagingModel.QUEUE, "dataRequest", "replyState", null);
        // @100 Send from initiator
        mt.setOutgoingTimestamp(100);

        // :: "Send and Receive"
        MatsTrace mt2 = reserialize(mt, ser);

        // RECEIVE: "Leaf"
        // @200 Receive on Leaf
        mt2.setStageEnteredTimestamp(200);
        // :: Assert current situation
        // The incoming call should have the outgoing timestamp
        Assert.assertEquals(100, mt2.getCurrentCall().getCalledTimestamp());
        // This is a REQUEST, so it should not have a same-height outgoing timestamp
        Assert.assertEquals(-1, mt2.getSameHeightOutgoingTimestamp());
        // The same-height-entered should be our own entry
        Assert.assertEquals(200, mt2.getSameHeightEndpointEnteredTimestamp());

        // :: Perform a REPLY
        mt2 = mt2.addReplyCall("Leaf", "dataReply");
        // @300 Send reply
        mt2.setOutgoingTimestamp(300);

        // :: "Send and Receive"
        MatsTrace mt3 = reserialize(mt2, ser);

        // RECEIVE: "Terminator"
        // @400 Receive on terminator
        mt3.setStageEnteredTimestamp(400);
        // :: Assert current situation
        // The incoming call should have the outgoing timestamp
        Assert.assertEquals(300, mt3.getCurrentCall().getCalledTimestamp());
        // This is a REPLY, so it should get the timestamp from the initiation send
        Assert.assertEquals(100, mt3.getSameHeightOutgoingTimestamp());
        // The same-height-entered should be towards the initiation
        Assert.assertEquals(10, mt3.getSameHeightEndpointEnteredTimestamp());
    }

    /**
     * Init SEND to 2Stage -> REQUEST to singleStage -> singleStage REPLY -> receive on Stage1, end
     * <pre>
     * [Initiator]   - send
     * [Initial]     - request
     *     [Service] - reply
     * [Stage1]
     * </pre>
     */
    @Test
    public void initSendTo2Stage() {
        MatsSerializerJson ser = MatsSerializerJson.create();

        // :: Perform a SEND
        MatsTrace mt = ser.createNewMatsTrace("traceId", "flowId", KeepMatsTrace.FULL, false, false, 0, false);
        // @10 Initialized
        ((MatsTraceFieldImpl) mt).overrideInitiatingTimestamp_ForTests(10);
        mt = mt.addSendCall("from", "2Stage", MessagingModel.QUEUE, "dataSend", null);
        // @100 Send from initiator
        mt.setOutgoingTimestamp(100);

        // :: "Send and Receive"
        MatsTrace mt2 = reserialize(mt, ser);

        // RECEIVE: "2Stage" initial
        // @200 Receive on initial stage
        mt2.setStageEnteredTimestamp(200);
        // :: Assert current situation
        // The incoming call is a SEND
        Assert.assertEquals(CallType.SEND, mt2.getCurrentCall().getCallType());
        // The incoming call should have the outgoing timestamp
        Assert.assertEquals(100, mt2.getCurrentCall().getCalledTimestamp());
        // This is a SEND, so it should have same-height outgoing timestamp as the outgoing
        Assert.assertEquals(100, mt2.getSameHeightOutgoingTimestamp());
        // The same-height-entered should be our own entry
        Assert.assertEquals(200, mt2.getSameHeightEndpointEnteredTimestamp());

        // :: Perform a REQUEST to Leaf
        mt2 = mt2.addRequestCall("2Stage", "Leaf", MessagingModel.QUEUE, "2Stage.stage1", MessagingModel.QUEUE, "dataRequest", "2StageState", null);
        // @300 Send REQUEST
        mt2.setOutgoingTimestamp(300);

        // :: "Send and Receive"
        MatsTrace mt3 = reserialize(mt2, ser);

        // RECEIVE: "Leaf" single stage
        // @400 Receive on Leaf
        mt3.setStageEnteredTimestamp(400);
        // :: Assert current situation
        // The incoming call is a REQUEST
        Assert.assertEquals(CallType.REQUEST, mt3.getCurrentCall().getCallType());
        // The incoming call should have the outgoing timestamp
        Assert.assertEquals(300, mt3.getCurrentCall().getCalledTimestamp());
        // This is a REQUEST, so it should not have a same-height outgoing timestamp
        Assert.assertEquals(-1, mt3.getSameHeightOutgoingTimestamp());
        // The same-height-entered should be our own entry
        Assert.assertEquals(400, mt3.getSameHeightEndpointEnteredTimestamp());

        // :: Perform a REPLY to "2Stage.stage1"
        mt3 = mt2.addReplyCall("Leaf", "leafReplyData");
        // @500 Send REPLY
        mt3.setOutgoingTimestamp(500);

        // :: "Send and Receive"
        MatsTrace mt4 = reserialize(mt3, ser);

        // RECEIVE: "2Stage.stage1"
        // @600 Receive on 2Stage.stage1
        mt4.setStageEnteredTimestamp(600);
        // :: Assert current situation
        // The incoming call is a REPLY
        Assert.assertEquals(CallType.REPLY, mt4.getCurrentCall().getCallType());
        // The incoming call should have the outgoing timestamp
        Assert.assertEquals(500, mt4.getCurrentCall().getCalledTimestamp());
        // This is a REPLY, so it should have the same-height outgoing timestamp from "2Stage"
        Assert.assertEquals(300, mt4.getSameHeightOutgoingTimestamp());
        // The same-height-entered should be when "2Stage" entered
        Assert.assertEquals(200, mt4.getSameHeightEndpointEnteredTimestamp());
    }

    /**
     * Init SEND to 2Stage -> REQUEST to singleStage -> singleStage REPLY -> receive on Stage1, end
     * <pre>
     * [Initiator]         - Request
     *     [2Stage]        - Next
     *     [2Stage.stage1] - Reply
     * [Terminator]
     * </pre>
     */
    @Test
    public void initRequestNextReplyToTerminator() {
        MatsSerializerJson ser = MatsSerializerJson.create();

        // :: Perform a REQUEST
        MatsTrace mt = ser.createNewMatsTrace("traceId", "flowId", KeepMatsTrace.FULL, false, false, 0, false);
        // @10 Initialized
        ((MatsTraceFieldImpl) mt).overrideInitiatingTimestamp_ForTests(10);
        mt = mt.addRequestCall("from", "2Stage", MessagingModel.QUEUE, "Terminator", MessagingModel.QUEUE, "dataRequest", "replyState", null);
        // @100 Send from initiator
        mt.setOutgoingTimestamp(100);

        // :: "Send and Receive"
        MatsTrace mt2 = reserialize(mt, ser);

        // RECEIVE: "Leaf"
        // @200 Receive on Leaf
        mt2.setStageEnteredTimestamp(200);
        // :: Assert current situation
        // The incoming call should have the outgoing timestamp
        Assert.assertEquals(100, mt2.getCurrentCall().getCalledTimestamp());
        // This is a REQUEST, so it should not have a same-height outgoing timestamp
        Assert.assertEquals(-1, mt2.getSameHeightOutgoingTimestamp());
        // The same-height-entered should be our own entry
        Assert.assertEquals(200, mt2.getSameHeightEndpointEnteredTimestamp());

        // :: Perform a NEXT
        mt2 = mt2.addNextCall("2Stage", "2Stage.stage1", "dataToStage1", "stateFromStage1");
        // @300 Send Next
        mt2.setOutgoingTimestamp(300);

        // :: "Send and Receive"
        MatsTrace mt3 = reserialize(mt2, ser);

        // RECEIVE: "Leaf"
        // @400 Receive on Leaf
        mt3.setStageEnteredTimestamp(400);
        // :: Assert current situation
        // The incoming call should have the outgoing timestamp
        Assert.assertEquals(300, mt3.getCurrentCall().getCalledTimestamp());
        // This is a NEXT, so it should have the "same height outgoing" from previous stage
        Assert.assertEquals(300, mt3.getSameHeightOutgoingTimestamp());
        // The same-height-entered should be entry from initial stage
        Assert.assertEquals(200, mt3.getSameHeightEndpointEnteredTimestamp());

        // :: Perform a REPLY
        mt3 = mt3.addReplyCall("single", "dataReply");
        // @300 Send reply
        mt3.setOutgoingTimestamp(500);

        // :: "Send and Receive"
        MatsTrace mt4 = reserialize(mt3, ser);

        // RECEIVE: "Terminator"
        // @400 Receive on terminator
        mt4.setStageEnteredTimestamp(600);
        // :: Assert current situation
        // The incoming call should have the outgoing timestamp
        Assert.assertEquals(500, mt4.getCurrentCall().getCalledTimestamp());
        // This is a REPLY, so it should get the timestamp from the initiation send
        Assert.assertEquals(100, mt4.getSameHeightOutgoingTimestamp());
        // The same-height-entered should be towards the initiation
        Assert.assertEquals(10, mt4.getSameHeightEndpointEnteredTimestamp());
    }

    private MatsTrace reserialize(MatsTrace mt, MatsSerializer ser) {
        SerializedMatsTrace serialized = ser.serializeMatsTrace(mt);
        return ser.deserializeMatsTrace(serialized.getMatsTraceBytes(), serialized.getMeta()).getMatsTrace();
    }
}
