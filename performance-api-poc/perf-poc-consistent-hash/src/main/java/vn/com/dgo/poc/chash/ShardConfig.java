package vn.com.dgo.poc.chash;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "shard")
public class ShardConfig {

    private List<NodeProps> nodes = new ArrayList<>();
    private int virtualNodesPerNode = 150;

    @Bean
    public ConsistentHashRing consistentHashRing() {
        ConsistentHashRing ring = new ConsistentHashRing(virtualNodesPerNode);
        nodes.forEach(n -> ring.addNode(new RedisNode(n.name, n.host, n.port)));
        return ring;
    }

    @Bean
    public ModuloRouter moduloRouter() {
        ModuloRouter router = new ModuloRouter();
        nodes.forEach(n -> router.addNode(new RedisNode(n.name, n.host, n.port)));
        return router;
    }

    public List<NodeProps> getNodes() {
        return nodes;
    }

    public void setNodes(List<NodeProps> nodes) {
        this.nodes = nodes;
    }

    public int getVirtualNodesPerNode() {
        return virtualNodesPerNode;
    }

    public void setVirtualNodesPerNode(int virtualNodesPerNode) {
        this.virtualNodesPerNode = virtualNodesPerNode;
    }

    public static class NodeProps {
        public String name;
        public String host;
        public int port;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }
}
