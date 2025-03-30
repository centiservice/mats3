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

package io.mats3.util;

import java.util.LinkedHashMap;
import java.util.Map.Entry;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsInitiator.InitiateLambda;

/**
 * A small tool to produce an opinionated TraceId - an example of such a string could be
 * <code>"Order.placeOrder:calcShipping[cid:123456][oid:654321]iu4m3p</code> - since this class implements
 * {@link CharSequence}, it can be used directly as the 'traceId' argument in initiations.
 * <p/>
 * The tool accepts null for any of the three "mandatory" pieces "appShort", "initShort" and "reason".
 * <p/>
 * <b>Remember that TraceIds should be as short as possible!</b> Be conservative with the lengths of these constituent
 * strings, and key names, and key values! Don't use UUIDs, they are typically both way too unique, and way too long
 * since they only use a limited alphabet.
 * <ol>
 * <li>{@code appShort}: A short-form of the application name - i.e. which service started this Mats Flow.</li>
 * <li>{@code initShort}: A short-form of the InitiatorId - i.e. some clue as to where in the service's codebase this
 * originates.</li>
 * <li>{@code reason}: An element of "reason" behind for creating this Flow, either an element of "to", i.e. which
 * service this targets. Or if multiple flows originate from this initiatorId-point, then a "sub-init" element.</li>
 * <li>{@link #batchId(long) batchId(..)}: If this Mats Flow is a part of several that are sent out in a batch, then you
 * may employ this to group them together.</li>
 * </ol>
 * <p/>
 * The special factory variants {@link #concat(String, String, String) concat(..)} and
 * {@link #fork(String, String, String) fork(..)} are used if you already have some kind of eventId or traceId which you
 * want to hook into the new Mats Flow. If you want to add contextual information on the existing eventId for a new
 * single Mats Flow, then you use <code>concat(..)</code> (uses a "+"). If this one single event leads to multiple new
 * flows, then you use <code>fork(..)</code> (uses a "|" - which is done automatically for you if you initiate new
 * messages from within a Mats Flow, i.e. inside a Stage with {@link ProcessContext#initiate(InitiateLambda)
 * processContext.initiate(..)}.
 * <p/>
 * The only important thing with a TraceId is that it is unique, so that it is possible to uniquely find it in the
 * distributed logging system. All else is only relevant for understanding when you need to reason about a specific
 * flow, typically in a debugging scenario, e.g. why did this end up on a Dead Letter Queue. It is extremely nice to
 * then immediately understand quite a bit out from the TraceId alone.
 * <p/>
 * Note: It is possible to extend this concept into domain-specific tools which add more specialized
 * {@link #add(String, long) add(key, value)}-variants in that the key is specified. E.g. if you in many contexts have
 * customerId and an orderId, it is nice if this always uses the same key "custId" (or "cid") and "orderId" (or "oid"),
 * instead of this varying between the initiating services. Thus you add a method <code>customerId(value)</code> and
 * <code>orderId(value)</code>, which just forwards to the add-method.
 * <p/>
 * Note: You can modify and reuse a TraceId object, e.g. change the 'reason' and perform a new initialization. Mats will
 * perform a {@link #toString()} on the instance when
 * <p/>
 * <b>There's a document about these identifiers at Mats3's github:
 * <a href="https://github.com/centiservice/mats3/blob/main/docs/TraceIdsAndInitiatorIds.md">TraceIds and
 * InitiatorIds.</a></b>
 *
 * @author Endre Stølsvik 2022-02-23 12:49 - http://stolsvik.com/, endre@stolsvik.com
 */
public class TraceId implements CharSequence {

    public static TraceId create(String appShort, String initShort) {
        return new TraceId(appShort, initShort, null);
    }

    public static TraceId create(String appShort, String initShort, String reason) {
        return new TraceId(appShort, initShort, reason);
    }

    public static TraceId concat(String prefix, String appShort, String initShort) {
        TraceId traceId = new TraceId(appShort, initShort, null);
        traceId.concat(prefix);
        return traceId;
    }

    public static TraceId concat(String prefix, String appShort, String initShort, String reason) {
        TraceId traceId = new TraceId(appShort, initShort, reason);
        traceId.concat(prefix);
        return traceId;
    }

    public static TraceId fork(String prefix, String appShort, String initShort) {
        TraceId traceId = new TraceId(appShort, initShort, null);
        traceId.fork(prefix);
        return traceId;
    }

    public static TraceId fork(String prefix, String appShort, String initShort, String reason) {
        TraceId traceId = new TraceId(appShort, initShort, reason);
        traceId.fork(prefix);
        return traceId;
    }

    /**
     * Reset the 'reason' part.
     */
    public TraceId reason(String reason) {
        _modified = true;
        _reason = reason;
        return this;
    }

    /**
     * Set the "batch" parameter, which always will be the first of the key-value props.
     */
    public TraceId batchId(String batchId) {
        _modified = true;
        _batchId = batchId;
        return this;
    }

    /**
     * Set the "batch" parameter, which always will be the first of the key-value props.
     */
    public TraceId batchId(long batchId) {
        return batchId(Long.toString(batchId));
    }

    /**
     * Add a key-value property - the addition order will be kept.
     */
    public TraceId add(String key, String value) {
        _modified = true;
        if (_keyValues == null) {
            _keyValues = new LinkedHashMap<>();
        }
        _keyValues.put(key, value);
        return this;
    }

    /**
     * Add a key-value property - the addition order will be kept.
     */
    public TraceId add(String key, long value) {
        return add(key, Long.toString(value));
    }

    /**
     * Adds a "!" (exclamation) character in front of the TraceId, which tells Mats that it should not prepend with the
     * current TraceId in a Stage-Initiate setting - note that a leading exclamation character will be removed in any
     * case.
     */
    public TraceId preventPrepend() {
        _preventPrepend = true;
        return this;
    }

    // =========== Implementation

    private boolean _modified = true; // Start modified.

    private boolean _preventPrepend;

    private final String _fromApp;
    private final String _fromInit;

    private String _prefix;

    private String _reason;

    private String _batchId;

    private LinkedHashMap<String, String> _keyValues;

    private String _calcualatedString;

    protected TraceId(String fromApp, String fromInit, String reason) {
        _fromApp = fromApp;
        _fromInit = fromInit;
        _reason = reason;
    }

    protected TraceId concat(String prefix) {
        _prefix = prefix + "+";
        return this;
    }

    protected TraceId fork(String prefix) {
        _prefix = prefix + "|";
        return this;
    }

    @Override
    public String toString() {
        // ?: Has it been modified? (Always true at construction)
        if (!_modified) {
            // No, not modified - so return what we already have.
            return _calcualatedString;
        }

        // E-> Yes, it is modified.
        // "Calculate" the resulting TraceId.
        StringBuilder buf = new StringBuilder();
        if (_preventPrepend) {
            buf.append('!');
        }
        if (_prefix != null) {
            buf.append(_prefix);
        }
        if (_fromApp != null) {
            buf.append(_fromApp);
        }
        if ((_fromApp != null) && (_fromInit != null)) {
            buf.append(".");
        }
        if (_fromInit != null) {
            buf.append(_fromInit);
        }
        if (((_fromApp != null) || (_fromInit != null)) && (_reason != null)) {
            buf.append(":");
        }
        if (_reason != null) {
            buf.append(_reason);
        }

        if (_batchId != null) {
            buf.append("[batch=").append(_batchId).append(']');
        }

        // ?: Do we have key-values?
        if (_keyValues != null) {
            // -> Yes, there are key-values, so add them.
            for (Entry<String, String> entry : _keyValues.entrySet()) {
                buf.append('[').append(entry.getKey()).append('=').append(entry.getValue()).append(']');
            }
        }
        else {
            // -> No key-values, so add an underscore before random part.
            buf.append('_');
        }
        buf.append(RandomString.partTraceId());

        _calcualatedString = buf.toString();
        _modified = false;

        return _calcualatedString;
    }

    // CharSequence

    @Override
    public int length() {
        return toString().length();
    }

    @Override
    public char charAt(int index) {
        return toString().charAt(index);
    }

    @Override
    public String subSequence(int start, int end) {
        return toString().substring(start, end);
    }
}
