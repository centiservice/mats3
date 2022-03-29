package io.mats3.util;

import java.util.LinkedHashMap;

import io.mats3.MatsEndpoint.ProcessContext;
import io.mats3.MatsInitiator.InitiateLambda;

/**
 * A small tool to produce a String - specifically an opinionated TraceId. An example of such a string could be
 * <code>"Order.placeOrder:calcShipping[cid:123456][oid:654321]iu4m3p</code>.
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
 * flows, then you use <code>form(..)</code> (uses a "|" - which is done automatically for you if you initiate new
 * messages from within a Mats Flow, i.e. inside a Stage with {@link ProcessContext#initiate(InitiateLambda)
 * processContext.initiate(..)}.
 * <p/>
 * The only important thing with a TraceId is that it is unique. All else is only relevant for understanding when you
 * need to reason about a specific flow, typically in a debugging scenario, e.g. why did this end up on a Dead Letter
 * Queue. It is extremely nice to then immediately understand quite a bit out from the TraceId alone.
 * <p/>
 * Note: It is possible to extend this concept into domain-specific tools which add more specialized
 * {@link #add(String, long) add(key, value)}-variants in that the key is specified. E.g. if you in many contexts have
 * customerId and an orderId, it is nice if this always uses the same key "custId" and "orderId", instead of this
 * varying between the initiating services. Thus you add a method <code>customerId(value)</code> and
 * <code>orderId(value)</code>, which just forwards to the add-method.
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

    public TraceId batchId(String batchId) {
        if (batchId == null) {
            return batchId("");
        }
        _batchId = batchId.length() == 0 ? "" : "[batch=" + batchId + ']';
        _parts[2] = _batchId;
        return this;
    }

    public TraceId batchId(long batchId) {
        return batchId(Long.toString(batchId));
    }

    public TraceId add(String key, String value) {
        if (_keyValues == null) {
            _keyValues = new LinkedHashMap<>();
        }
        String oldValue = _keyValues.put(key, value);
        String keyValueQouted = '[' + key + '=' + value + ']';
        if (oldValue == null) {
            _keyValuesText.append(keyValueQouted);
        }
        else {
            String oldKeyValueQuoted = '[' + key + '=' + oldValue + ']';
            int oldStart = _keyValuesText.indexOf(oldKeyValueQuoted);
            int oldEnd = oldKeyValueQuoted.length();
            _keyValuesText.replace(oldStart, oldEnd, keyValueQouted);
        }
        return this;
    }

    public TraceId add(String key, long value) {
        return add(key, Long.toString(value));
    }

    // =========== Implementation

    private final String _header;

    private LinkedHashMap<String, String> _keyValues;
    private final StringBuilder _keyValuesText = new StringBuilder();

    private String _batchId = "";
    private String _prefix = "";

    private final String _randomPart = RandomString.partTraceId();

    // All the parts of the TraceId, used to implement CharSequence methods. When _batchId or _prefix is changed, then
    // the corresponding reference in the parts array must also be updated so that the CharSequence contract remains
    // correct.
    private final CharSequence[] _parts;


    protected TraceId(String fromApp, String fromInit, String reason) {
        StringBuilder buf = new StringBuilder();
        if (fromApp != null) {
            buf.append(fromApp);
        }
        if ((fromApp != null) && (fromInit != null)) {
            buf.append(".");
        }
        if (fromInit != null) {
            buf.append(fromInit);
        }
        if (((fromApp != null) || (fromInit != null)) && (reason != null)) {
            buf.append(":");
        }
        if (reason != null) {
            buf.append(reason);
        }
        _header = buf.toString();
        _parts = new CharSequence[] {_prefix, _header, _batchId, _keyValuesText, _randomPart};
    }

    protected TraceId concat(String prefix) {
        _prefix = prefix + "+";
        _parts[0] = _prefix;
        return this;
    }

    protected TraceId fork(String prefix) {
        _prefix = prefix + "|";
        _parts[0] = _prefix;
        return this;
    }

    @Override
    public int length() {
        int length = 0;
        for (CharSequence _part : _parts) {
            length += _part.length();
        }
        return length;
    }

    @Override
    public char charAt(int index) {
        int pos = 0;
        for (CharSequence part : _parts) {
            if (index < part.length() + pos) {
                return part.charAt(index - pos);
            }
            pos += part.length();
        }
        throw new IndexOutOfBoundsException("Index out of range: " + index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        if (start == end) {
            return "";
        }
        int length = length();
        if (start > end) {
            throw new IllegalArgumentException("Start [" + start + "] must be before end [" + end + "]");
        }
        if (start >= length) {
            throw new IndexOutOfBoundsException("Start out of range: " + start);
        }
        if (end > length) {
            throw new IndexOutOfBoundsException("End out of range: " + end);
        }
        if (start == 0 && end == length) {
            return this;
        }

        StringBuilder buf = new StringBuilder();
        // Go through each part,
        int partStart = 0;
        for (CharSequence part : _parts) {
            int partEnd = partStart + part.length();
            int subPartStart = Math.max(start - partStart, 0);
            int subPartEnd = Math.min(end - partStart, part.length());
            if (subPartStart < part.length() && subPartEnd >= 0) {
                buf.append(part, subPartStart, subPartEnd);
            }
            partStart = partEnd;
        }

        return buf.toString();
    }

    @Override
    public String toString() {
        return String.join("", _parts);
    }
}
