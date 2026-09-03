package com.claude.emqx.sparkplug;

/**
 * Sparkplug B v3 topic structure:
 * <pre>
 *   spBv1.0/{group_id}/{msg_type}/{edge_node_id}[/{device_id}]
 * </pre>
 * msg_type ∈ { NBIRTH, NDATA, NDEATH, NCMD,    -- node-level
 *              DBIRTH, DDATA, DDEATH, DCMD,    -- device-level
 *              STATE }                          -- host application state
 */
public record SparkplugTopic(String groupId, String msgType, String edgeNodeId, String deviceId) {

    public static final String NAMESPACE = "spBv1.0";

    public String render() {
        return deviceId == null
                ? "%s/%s/%s/%s".formatted(NAMESPACE, groupId, msgType, edgeNodeId)
                : "%s/%s/%s/%s/%s".formatted(NAMESPACE, groupId, msgType, edgeNodeId, deviceId);
    }

    public static SparkplugTopic parse(String topic) {
        String[] p = topic.split("/");
        if (p.length < 4 || !NAMESPACE.equals(p[0])) {
            throw new IllegalArgumentException("not a sparkplug topic: " + topic);
        }
        return new SparkplugTopic(p[1], p[2], p[3], p.length >= 5 ? p[4] : null);
    }

    public static SparkplugTopic node(String group, String msgType, String node) {
        return new SparkplugTopic(group, msgType, node, null);
    }

    public static SparkplugTopic device(String group, String msgType, String node, String device) {
        return new SparkplugTopic(group, msgType, node, device);
    }
}
