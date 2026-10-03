package com.shopagent.infra.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TwoLevelCacheTest {

    private final RedissonClient redisson = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RBucket<String> bucket = mock(RBucket.class);
    private final RAtomicLong counter = mock(RAtomicLong.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger dbHits = new AtomicInteger();

    private TwoLevelCache<Long, ProductVO> cache;

    record ProductVO(Long id, String name, BigDecimal price) {}

    @BeforeEach
    void setUp() {
        cache = new TwoLevelCache<>(redisson, objectMapper, "test", "cache:test:",
                10, Duration.ofSeconds(60), Duration.ofMinutes(30), ProductVO.class);
        doReturn(bucket).when(redisson).getBucket(anyString(), any());
        doReturn(counter).when(redisson).getAtomicLong(anyString());
        when(counter.incrementAndGet()).thenReturn(1L);
        when(counter.get()).thenReturn(42L);
        dbHits.set(0);
    }

    private ProductVO vo(long id) {
        return new ProductVO(id, "便携式露营灯", new BigDecimal("59.00"));
    }

    private ProductVO loader(Long id) {
        dbHits.incrementAndGet();
        return vo(id);
    }

    @Test
    void 首读回填_再读L1命中_零DB零Redis读() throws Exception {
        when(bucket.get()).thenReturn(null);

        cache.get(7L, this::loader);
        cache.get(7L, this::loader);

        assertThat(dbHits.get()).isEqualTo(1);
        verify(bucket, times(1)).get();
        // 回填证据：L2 set 带上 TTL（L2 先），第二次 get 走 L1（L1 后都发生）
        verify(bucket).set(objectMapper.writeValueAsString(vo(7L)), Duration.ofMinutes(30));
    }

    @Test
    void L2命中_不回源_且回填L1() throws Exception {
        when(bucket.get()).thenReturn(objectMapper.writeValueAsString(vo(7L)));

        ProductVO first = cache.get(7L, this::loader);
        ProductVO second = cache.get(7L, this::loader);

        assertThat(first).isEqualTo(second);
        assertThat(dbHits.get()).isZero();
        verify(bucket, times(1)).get();
    }

    @Test
    void evict双删_再读重新回源() {
        when(bucket.get()).thenReturn(null);

        cache.get(7L, this::loader);
        cache.evict(7L);
        cache.get(7L, this::loader);

        verify(bucket).delete();
        assertThat(dbHits.get()).isEqualTo(2);
    }

    @Test
    void L2读故障_降级loader不外抛() {
        when(bucket.get()).thenThrow(new RuntimeException("redis down"));

        ProductVO value = cache.get(7L, this::loader);

        assertThat(value).isEqualTo(vo(7L));
        assertThat(dbHits.get()).isEqualTo(1);
    }

    @Test
    void L2写故障_L1照常回填_读路径零损失() {
        // 「L2 先 L1 后」的可观察后果：L2 set 失败不放弃本地回填，第二次 get 仍是 L1 命中
        when(bucket.get()).thenReturn(null);
        doThrow(new RuntimeException("redis down")).when(bucket).set(anyString(), any(Duration.class));

        cache.get(7L, this::loader);
        cache.get(7L, this::loader);

        assertThat(dbHits.get()).isEqualTo(1);
    }

    @Test
    void 查无null_不缓存负结果_下次仍回源() {
        when(bucket.get()).thenReturn(null);

        assertThat(cache.get(999L, id -> {
            dbHits.incrementAndGet();
            return null;
        })).isNull();
        assertThat(cache.get(999L, id -> {
            dbHits.incrementAndGet();
            return null;
        })).isNull();

        assertThat(dbHits.get()).isEqualTo(2);
        verify(bucket, never()).set(anyString(), any(Duration.class));
    }

    @Test
    void 热点计数_穿透时打标_L1命中不加_读数暴露() {
        when(bucket.get()).thenReturn(null);

        cache.get(7L, this::loader);
        cache.get(7L, this::loader);

        // 仅首次穿透打标一次（L1 命中不打扰 Redis）
        verify(counter, times(1)).incrementAndGet();
        assertThat(cache.hotspotCount(7L)).isEqualTo(42L);
    }

    @Test
    void 热点计数_Redis故障_读数降级为负一不外抛() {
        when(redisson.getAtomicLong(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(cache.hotspotCount(7L)).isEqualTo(-1L);
    }
}
