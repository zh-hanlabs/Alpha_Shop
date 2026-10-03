package com.shopagent.infra.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Function;

/**
 * 两级缓存（W5D3 §2.4 定稿，手写不用 Spring Cache 抽象——面试要讲清每一层，抽象反而藏细节）。
 * 读路径 L1 → L2 → loader(库)；L2 故障 fail-open 降级（缓存是加速器不是正确性来源）。
 * 热点打标：L1 未命中（穿透到 L2/库）时 Redis INCR 计数，只做指标暴露不做调度——
 * 口径 = 跨进程数据访问量（L1 进程内命中不打扰 Redis）；打标是手段，淘汰策略才是调度，数字 W7 JMeter 出。
 */
public class TwoLevelCache<K, V> {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);
    private static final String HOTSPOT_KEY_PREFIX = "cache:hotspot:";
    private static final Duration HOTSPOT_TTL = Duration.ofHours(1);

    private final Cache<K, V> l1;
    private final RedissonClient redisson;
    private final ObjectMapper objectMapper;
    private final String name;
    private final String l2KeyPrefix;
    private final Duration l2Ttl;
    private final Class<V> valueType;

    public TwoLevelCache(RedissonClient redisson, ObjectMapper objectMapper, String name,
                         String l2KeyPrefix, long l1MaxSize, Duration l1Ttl, Duration l2Ttl,
                         Class<V> valueType) {
        this.l1 = Caffeine.newBuilder().maximumSize(l1MaxSize).expireAfterWrite(l1Ttl).build();
        this.redisson = redisson;
        this.objectMapper = objectMapper;
        this.name = name;
        this.l2KeyPrefix = l2KeyPrefix;
        this.l2Ttl = l2Ttl;
        this.valueType = valueType;
    }

    public V get(K key, Function<K, V> loader) {
        V cached = l1.getIfPresent(key);
        if (cached != null) {
            log.debug("L1 hit: {} key={}", name, key);
            return cached;
        }
        markHotspot(key);
        RBucket<String> bucket = redisson.getBucket(l2KeyPrefix + key, StringCodec.INSTANCE);
        try {
            String json = bucket.get();
            if (json != null) {
                V value = objectMapper.readValue(json, valueType);
                l1.put(key, value);
                log.info("L2 hit: {} key={}", name, key);
                return value;
            }
        } catch (Exception e) {
            log.warn("L2 read failed, degrade to loader: {} key={}", name, key, e);
        }
        V loaded = loader.apply(key);
        if (loaded != null) {
            fill(key, loaded, bucket);
        }
        return loaded;
    }

    /**
     * 回填顺序 L2 先 L1 后（§2.4）：先落跨实例层，本地层最后——
     * 中途故障（L2 写失败）时本地层照常回填（读路径零损失），新值至少能被其他实例从 L2 看见。
     * L2 用 JSON 字符串存储：redis-cli 直读可演示内容，跨服务类型歧义归零。
     */
    private void fill(K key, V value, RBucket<String> bucket) {
        try {
            bucket.set(objectMapper.writeValueAsString(value), l2Ttl);
        } catch (Exception e) {
            log.warn("L2 fill failed (accelerator only): {} key={}", name, key, e);
        }
        l1.put(key, value);
    }

    /**
     * Cache Aside 的「删」：L1 失效 + L2 删除（双删）。
     * L2 删除失败只留 TTL 上限的脏窗口——本地层已即时失效，正确性仍由先更库保证（最终一致）。
     */
    public void evict(K key) {
        l1.invalidate(key);
        try {
            redisson.getBucket(l2KeyPrefix + key, StringCodec.INSTANCE).delete();
            log.info("cache evict: {} key={}（L1+L2 双删）", name, key);
        } catch (Exception e) {
            log.warn("L2 evict failed, dirty window bounded by TTL: {} key={}", name, key, e);
        }
    }

    /** 热点计数读数（指标暴露用），Redis 不可用返回 -1 */
    public long hotspotCount(K key) {
        try {
            return redisson.getAtomicLong(HOTSPOT_KEY_PREFIX + name + ":" + key).get();
        } catch (Exception e) {
            return -1;
        }
    }

    private void markHotspot(K key) {
        try {
            RAtomicLong counter = redisson.getAtomicLong(HOTSPOT_KEY_PREFIX + name + ":" + key);
            counter.incrementAndGet();
            counter.expireIfNotSet(HOTSPOT_TTL);
        } catch (Exception e) {
            log.debug("hotspot mark failed (best-effort metric): {} key={}", name, key, e);
        }
    }
}
