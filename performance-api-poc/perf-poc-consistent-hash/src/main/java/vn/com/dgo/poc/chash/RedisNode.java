package vn.com.dgo.poc.chash;

public record RedisNode(String name, String host, int port) {

    public String identity() {
        return name + "@" + host + ":" + port;
    }
}
