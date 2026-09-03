package vn.com.dgo.poc.chash;

import java.util.List;

/**
 * Strategy chung cho cả consistent-hash ring lẫn naive modulo router.
 * Dùng để compare remap rate khi topology thay đổi.
 */
public interface KeyRouter {

    void addNode(RedisNode node);

    void removeNode(RedisNode node);

    RedisNode route(String key);

    List<RedisNode> nodes();

    String name();
}
