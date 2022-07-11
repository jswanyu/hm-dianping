package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * @Classname: CacheClient
 * @author: wanyu
 * @Date: 2022/7/10 11:32
 */

@Slf4j
@Component
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // redis缓存，设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    // redis缓存，设置逻辑过期
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time))); // 逻辑过期：当前时间+期望时间
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 根据指定类型的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    // 之前返回类型固定为店铺，现在使用泛型R，参数里加上Class<R> type，表示手动传入对象类型，进行泛型推断
    // keyPrefix是redis里的前缀，前缀+id，才是redis里的key。ID也不一定为Long型，所以也使用泛型
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询对象类型的缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回。返回的类型不再是店铺，而是传进来的type
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值。这里很容易忘记，查到的缓存只有三种情况：查到(正常情况)；空值(“”)；为null
        // 所以不等于空就只能是空值了，上面的isNotBlank()方法是认定只有字符串有内容才返回true，比如“abc”。其他类似NULL(为空),""(空值)返回都是false
        // 或者直接写 if ("".equals(shopJson))
        if (json != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.缓存不存在，根据id查询数据库。这里需要格外关注，使用到了函数式编程，不同对象的数据库查询方法自然不同，这里既然不知道是什么对象类型，只能把问题抛给调用者
        // 所以我们在参数里加上函数，有参(参数类型ID)有返回值(返回值类型R)，起名dbFallback，apply方法就是调用这个函数，并把id作为参数传进去
        R r = dbFallback.apply(id);
        // 5.DB里也不存在，返回错误
        if (r == null) {
            // 将空值写入redis，避免缓存穿透。TTL为2min
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6.DB里存在，写入redis，调用了前面的普通设置缓存的方法，还需要参数时间和单位
        this.set(key, r, time, unit);
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 根据指定类型的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题
    // 细节同上，不赘述
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.存在，直接返回
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回店铺信息
            return r;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock){
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R r1 = dbFallback.apply(id);
                    // 重建缓存
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4.返回过期的商铺信息
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
