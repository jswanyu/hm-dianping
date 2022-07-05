package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    // 静态代码块加载lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 创建线程池，这里只创建了一个单线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 单独线程应该一开始就执行任务，不断去消息队列里取。所以这里是写成类一初始化结束，就开启线程任务
    // @PostConstruct注解是在当前类初始化完毕之后，立刻执行该方法
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 创建单独线程要执行的任务
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),  // 消费者组里的哪个消费者
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),  // 读1条消息，阻塞2s
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())  // 指定消息队列，以及是读取最新一条未处理的
                    );
                    // 2.判断消息获取是否成功，即订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析消息中的订单数据
                    MapRecord<String, Object, Object> record = list.get(0); // 第一个string是消息id，后面两个是消息的内容，即lua脚本里写的键值对
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // 5.死循环在正常情况下读取下一个未消费的消息，出现异常（即消息处理完之后没有返回ACK确认）那就再来一个死循环
                    // 去处理pending-list中已消费但未确认的消息，处理完了再回到正常流程的死循环
                    handlePendingList();
                }
            }
        }

        // 流程和上面类似
        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1), // 读 PendingList 不用阻塞
                            StreamOffset.create("stream.orders", ReadOffset.from("0")) // 读 PendingList 一定读已读未完成的消息
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有异常消息，结束循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理PendingList订单异常", e);
                }
            }
        }
    }


    // 先去redis里完成抢单
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本，使用的是stringRedisTemplate.execute()方法
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),  // 这里不用传redis的key，所以传一个空列表，不能传null
                voucherId.toString(), userId.toString(), String.valueOf(orderId)  // 这里传其他参数（即非key的参数），以字符串形式
        );
        int r = result.intValue(); // 转成int型
        // 2.判断结果是否为0
        if (r != 0) {
            // 2.1.不为0 ，代表没有购买资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 3.返回订单id
        return Result.ok(orderId);
    }


    // 从阻塞队列里获取订单，创建秒杀订单
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 1.获取用户
        // 这里获取用户id就不能去当前线程里取了，因为我们是单独开了线程去处理队列里的订单，不是主线程
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 2.创建锁对象
        // 事实上这里不用锁也可以，因为前面redis处理完判断有下单资格之后就不会出现问题了，这里做了一个兜底
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3.尝试获取锁
        boolean isLock = lock.tryLock();
        // 4.判断锁是否获取成功
        if (!isLock) {
            // 4.1 获取锁失败，打印错误信息，这里不用返回给前端了
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 5.1.查询订单
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 5.2.判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                log.error("不允许重复下单！");
                return;
            }

            // 6.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足！");
                return;
            }

            // 7.创建订单
            save(voucherOrder);
        } finally {
            // 释放锁
            lock.unlock();
        }
    }


/*    // 创建简单的阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    // 创建单独线程要执行的任务
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    // 1.获取队列中的订单信息
                    // take方法表示获取阻塞队列里的内容，没有则等待，所以不用担心while(true)会很占用资源
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    createVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/


/*    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠券:去秒杀券表里查
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀尚未开始！");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            // 尚未开始
            return Result.fail("秒杀已经结束！");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1) {
            // 库存不足
            return Result.fail("库存不足！");
        }

        // 分布式锁
        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        // SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate); // 之前自己写的锁
        RLock lock = redissonClient.getLock("lock:order:" + userId); // 这里是可重入的锁
        // 获取锁，方便调试将超时时间设置长一点，实际上要根据业务执行时间来定，比如业务只用执行500ms，设置个5s最多了
        // redisson的tryLock方法可以设置三个参数，分别是：获取锁的最大等待时间（期间会重试），锁自动释放时间，时间单位
        // 无参默认情况是：不重试、自动超时为30s，我们代码里的逻辑也是不重试，所以就选择无参的情况
        boolean isLock = lock.tryLock();
        // 判断是否获取锁成功
        if(!isLock){
            // 获取锁失败，返回错误提示
            return Result.fail("不允许重复下单！");
        }
        // 防止出现异常，放到try-finally里，最终释放锁
        try {
            // 获得代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);  // 用代理对象去调用加了事务管理注解的方法
        } finally {
            // 释放锁
            lock.unlock();
        }
    }*/


/*    @Transactional  // 涉及到了秒杀券表和优惠券订单表
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();
        // 5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 5.2 判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("the user had bought !");
        }
        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock >0
                .update();
        if (!success) {
            // 扣减失败
            return Result.fail("the stock is not enough！");
        }
        // 7.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1.订单id，调用全局ID生成器
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2.用户id
        voucherOrder.setUserId(userId);
        // 7.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        // 8.返回订单id
        return Result.ok(orderId);
    }*/
}
