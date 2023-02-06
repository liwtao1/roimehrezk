package us.codecraft.webmagic.scheduler;

import org.apache.commons.codec.digest.DigestUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import us.codecraft.webmagic.Request;
import us.codecraft.webmagic.Task;
import us.codecraft.webmagic.schedular.Scheduler;

import java.io.IOException;

/**
 * 使用redis管理url，构建一个分布式的爬虫。<br>
 *
 * @author code4crafter@gmail.com <br>
 * @date: 13-7-25 <br>
 * Time: 上午7:07 <br>
 */
public class RedisScheduler implements Scheduler {

    private JedisPool pool;

    private static final String QUEUE_PREFIX = "queue_";

    private static final String SET_PREFIX = "set_";

    private static final String ITEM_PREFIX = "item_";

    public RedisScheduler(String host) {
        pool = new JedisPool(new JedisPoolConfig(), host);
    }

    @Override
    public synchronized void push(Request request, Task task) {
        Jedis jedis = pool.getResource();
        //使用SortedSet进行url去重
        if (jedis.zrank(SET_PREFIX + task.getUUID(), request.getUrl()) == null) {
            //使用List保存队列
            jedis.rpush(QUEUE_PREFIX + task.getUUID(), request.getUrl());
            jedis.zadd(SET_PREFIX + task.getUUID(), request.getPriority(), request.getUrl());
            if (request.getExtras() != null) {
                String key = ITEM_PREFIX + DigestUtils.shaHex(request.getUrl());
                try {
                    byte[] serialize = HessianSerializer.INSTANCE.serialize(request);
                    jedis.set(key.getBytes(), serialize);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        pool.returnResource(jedis);
    }

    @Override
    public synchronized Request poll(Task task) {
        Jedis jedis = pool.getResource();
        String url = jedis.lpop(QUEUE_PREFIX + task.getUUID());
        if (url == null) {
            return null;
        }
        String key = ITEM_PREFIX + DigestUtils.shaHex(url);
        byte[] bytes = jedis.get(key.getBytes());
        if (bytes!=null){
            try {
                Object o = HessianSerializer.INSTANCE.deSerialize(bytes);
                return (Request)o;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        pool.returnResource(jedis);
        return new Request(url);
    }
}
