package com.claude.emqx.sparkplug;

import org.eclipse.tahu.protobuf.SparkplugBProto.Payload;
import org.eclipse.tahu.protobuf.SparkplugBProto.Payload.Metric;

import java.util.Map;

/**
 * Thin convenience wrapper over the generated protobuf classes.
 *
 * <p>Sparkplug B encodes everything as a {@link Payload} of {@link Metric}s plus a
 * monotonic {@code seq} (0-255 wrap). The host application uses {@code seq} to
 * detect dropped messages between an NBIRTH and the next NDEATH.
 */
public final class PayloadCodec {

    private PayloadCodec() {}

    /** bdSeq metric name (Sparkplug B spec, section 5.4.1). */
    public static final String BD_SEQ = "bdSeq";

    public static Payload.Builder newPayload(long seq) {
        return Payload.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setSeq(seq);
    }

    /** Build a typed metric. Sparkplug requires the datatype tag explicitly. */
    public static Metric metric(String name, SparkplugDataType type, Object value) {
        Metric.Builder b = Metric.newBuilder()
                .setName(name)
                .setTimestamp(System.currentTimeMillis())
                .setDatatype(type.id);
        if (value == null) {
            b.setIsNull(true);
            return b.build();
        }
        switch (type) {
            case INT8, INT16, INT32, UINT8, UINT16, UINT32 -> b.setIntValue(((Number) value).intValue());
            case INT64, UINT64, DATETIME                   -> b.setLongValue(((Number) value).longValue());
            case FLOAT                                     -> b.setFloatValue(((Number) value).floatValue());
            case DOUBLE                                    -> b.setDoubleValue(((Number) value).doubleValue());
            case BOOLEAN                                   -> b.setBooleanValue((Boolean) value);
            case STRING, TEXT, UUID                        -> b.setStringValue(value.toString());
            case BYTES, FILE                               -> b.setBytesValue(com.google.protobuf.ByteString.copyFrom((byte[]) value));
            default -> throw new IllegalArgumentException("unsupported type " + type);
        }
        return b.build();
    }

    /**
     * Decode a metric's value into a plain Java object, so the host application can
     * shove it into a Map<String,Object> without knowing the wire format.
     */
    public static Object decode(Metric m) {
        if (m.getIsNull()) return null;
        return switch (m.getValueCase()) {
            case INT_VALUE     -> m.getIntValue();
            case LONG_VALUE    -> m.getLongValue();
            case FLOAT_VALUE   -> m.getFloatValue();
            case DOUBLE_VALUE  -> m.getDoubleValue();
            case BOOLEAN_VALUE -> m.getBooleanValue();
            case STRING_VALUE  -> m.getStringValue();
            case BYTES_VALUE   -> m.getBytesValue().toByteArray();
            case VALUE_NOT_SET -> null;
        };
    }

    public static Map<String, Object> toMap(Payload p) {
        var out = new java.util.LinkedHashMap<String, Object>();
        for (Metric m : p.getMetricsList()) out.put(m.getName(), decode(m));
        return out;
    }
}
