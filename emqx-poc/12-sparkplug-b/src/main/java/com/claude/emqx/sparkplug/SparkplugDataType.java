package com.claude.emqx.sparkplug;

/**
 * Sparkplug B data type IDs as defined in the v3 spec, Appendix A.
 * These IDs go into Metric.datatype on the wire. The host application uses
 * the ID to decode the {@code oneof value} field correctly — protobuf has no
 * way to express "if datatype=3 then read int_value as uint32".
 */
public enum SparkplugDataType {
    UNKNOWN(0), INT8(1), INT16(2), INT32(3), INT64(4),
    UINT8(5), UINT16(6), UINT32(7), UINT64(8),
    FLOAT(9), DOUBLE(10), BOOLEAN(11), STRING(12),
    DATETIME(13), TEXT(14), UUID(15), BYTES(17),
    FILE(18);

    public final int id;
    SparkplugDataType(int id) { this.id = id; }
}
