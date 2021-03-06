package com.atguigu.gulimall.order.service.impl;

import com.alibaba.fastjson.TypeReference;
import com.atguigu.common.exception.NoStockException;
import com.atguigu.common.to.mq.OrderTo;
import com.atguigu.common.to.mq.SecKillOrderTo;
import com.atguigu.common.utils.PageUtils;
import com.atguigu.common.utils.Query;
import com.atguigu.common.utils.R;
import com.atguigu.common.vo.MemberRespVo;
import com.atguigu.gulimall.order.constant.OrderConstant;
import com.atguigu.gulimall.order.dao.OrderDao;
import com.atguigu.gulimall.order.entity.OrderEntity;
import com.atguigu.gulimall.order.entity.OrderItemEntity;
import com.atguigu.gulimall.order.entity.PaymentInfoEntity;
import com.atguigu.gulimall.order.enume.OrderStatusEnum;
import com.atguigu.gulimall.order.feign.CartFeignService;
import com.atguigu.gulimall.order.feign.MemberFeignService;
import com.atguigu.gulimall.order.feign.ProductFeignService;
import com.atguigu.gulimall.order.feign.WareFeignService;
import com.atguigu.gulimall.order.interceptor.LoginUserInterceptor;
import com.atguigu.gulimall.order.service.OrderItemService;
import com.atguigu.gulimall.order.service.OrderService;
import com.atguigu.gulimall.order.service.PaymentInfoService;
import com.atguigu.gulimall.order.to.OrderCreateTo;
import com.atguigu.gulimall.order.vo.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


@Service("orderService")
public class OrderServiceImpl extends ServiceImpl<OrderDao, OrderEntity> implements OrderService {

    private ThreadLocal<OrderSubmitVo> orderSubmitVoThreadLocal = new ThreadLocal<>();

    @Autowired
    OrderItemService orderItemService;

    @Autowired
    MemberFeignService memberFeignService;

    @Autowired
    CartFeignService cartFeignService;

    @Autowired
    WareFeignService wareFeignService;

    @Autowired
    ProductFeignService productFeignService;

    @Autowired
    PaymentInfoService paymentInfoService;

    @Autowired
    ThreadPoolExecutor executor;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<OrderEntity> page = this.page(
                new Query<OrderEntity>().getPage(params),
                new QueryWrapper<OrderEntity>()
        );

        return new PageUtils(page);
    }

    /**
     * ????????????????????????????????????
     * @return
     */
    @Override
    public OrderConfirmVo confirmOrder() throws ExecutionException, InterruptedException {
        OrderConfirmVo orderConfirmVo = new OrderConfirmVo();

        MemberRespVo memberRespVo = LoginUserInterceptor.threadLocal.get();

        /**
         * ???????????????????????????ThreadLocal????????????
         * ????????????????????????????????????????????????????????????ThreadLocal??????
         */
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();


        CompletableFuture<Void> getAddressTask = CompletableFuture.runAsync(() -> {

            RequestContextHolder.setRequestAttributes(requestAttributes); // ???????????????????????????ThreadLocal????????????

            // 1????????????????????????????????????
            List<MemberAddressVo> addresses = memberFeignService.getAddresses(memberRespVo.getId());
            orderConfirmVo.setAddressList(addresses);
        }, executor);

        CompletableFuture<Void> getCartTask = CompletableFuture.runAsync(() -> {

            RequestContextHolder.setRequestAttributes(requestAttributes); // ???????????????????????????ThreadLocal????????????

            // 2????????????????????????????????????????????????
            List<OrderItemVo> orderItems = cartFeignService.currentUserCartItems();
            orderConfirmVo.setItems(orderItems);
        }, executor).thenRunAsync(() -> {
            // ???????????????????????????
            List<OrderItemVo> items = orderConfirmVo.getItems();
            List<Long> collect = items.stream().map(item -> item.getSkuId()).collect(Collectors.toList());
            R r = wareFeignService.getSkusHasStock(collect);
            List<SkuStockVo> data = r.getData(new TypeReference<List<SkuStockVo>>() {
            });
            if (data != null) {
                Map<Long, Boolean> map = data.stream().collect(Collectors.toMap(SkuStockVo::getSkuId, SkuStockVo::getHasStock));
                orderConfirmVo.setStocks(map);
            }

        },executor);


        // 3?????????????????????
        Integer integration = memberRespVo.getIntegration();
        orderConfirmVo.setIntegration(integration);

        // TODO ????????????
        String token = UUID.randomUUID().toString().replace("-", "");
        orderConfirmVo.setOrderToken(token);

        stringRedisTemplate.opsForValue().set(OrderConstant.USER_ORDER_TOKEN_PREFIX + memberRespVo.getId(), token, 30, TimeUnit.MINUTES);


        CompletableFuture.allOf(getAddressTask, getCartTask).get(); // ??????????????????????????????

        return orderConfirmVo;
    }

    /**
     * ??????
     * @param orderSubmitVo
     * @return
     */
    @Transactional
    @Override
    public SubmitOrderRespVo submitOrder(OrderSubmitVo orderSubmitVo) {

        orderSubmitVoThreadLocal.set(orderSubmitVo);

        SubmitOrderRespVo submitOrderRespVo = new SubmitOrderRespVo();
        submitOrderRespVo.setCode(0);

        MemberRespVo memberRespVo = LoginUserInterceptor.threadLocal.get();

        // ?????????????????????token???redis?????????token????????????
        String redisOrderToken = stringRedisTemplate.opsForValue().get(OrderConstant.USER_ORDER_TOKEN_PREFIX + memberRespVo.getId());

        //  redis+lua?????? ??????????????????????????????????????????
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
        String orderToken = orderSubmitVo.getOrderToken();
        //  return 0 ??????  1 ??????
        Long result = stringRedisTemplate.execute(new DefaultRedisScript<Long>(script, Long.class),
                Arrays.asList(OrderConstant.USER_ORDER_TOKEN_PREFIX + memberRespVo.getId()), orderToken);

        if (result == 0) { // ??????????????????
            submitOrderRespVo.setCode(1);
            return submitOrderRespVo;
        } else { // ??????????????????
            OrderCreateTo order = createOrder();

            BigDecimal payAmount = order.getOrderEntity().getPayAmount();
            BigDecimal payPrice = orderSubmitVo.getPayPrice();
            if (Math.abs(payAmount.subtract(payPrice).doubleValue()) < 0.01) {
                // ????????????

                // ?????????????????????
                saveOrder(order);
                // ????????? ???????????????
                WareSkuLockVo wareSkuLockVo = new WareSkuLockVo();
                wareSkuLockVo.setOrderSn(order.getOrderEntity().getOrderSn());

                List<OrderItemVo> collect = order.getItems().stream().map(item -> {
                    OrderItemVo orderItemVo = new OrderItemVo();
                    orderItemVo.setSkuId(item.getSkuId());
                    orderItemVo.setCount(item.getSkuQuantity());
                    orderItemVo.setTitle(item.getSkuName());
                    return orderItemVo;
                }).collect(Collectors.toList());

                wareSkuLockVo.setLocks(collect);

                // ????????????????????????
                R r = wareFeignService.orderLocKStock(wareSkuLockVo);
                if (r.getCode() == 0) {
                    submitOrderRespVo.setOrderEntity(order.getOrderEntity());
                    // TODO ??????????????????????????????????????????
                    //int i = 10/0; // ???????????????????????????????????????????????????

                    // ??????????????????????????????
                    rabbitTemplate.convertAndSend("order-event-exchange", "order.create.order", order.getOrderEntity());

                    return submitOrderRespVo;
                } else {
                    // ????????????
                    String msg = (String) r.get("msg");
                    throw new NoStockException(msg);
                }

            } else {
                submitOrderRespVo.setCode(2);
                return submitOrderRespVo;
            }
        }



    }


    /**
     * ??????????????????
     * @param order
     */
    private void saveOrder(OrderCreateTo order) {

        OrderEntity orderEntity = order.getOrderEntity();
        orderEntity.setModifyTime(new Date());

        this.save(orderEntity);

        List<OrderItemEntity> items = order.getItems();
        for (OrderItemEntity item : items) {
            orderItemService.getBaseMapper().insert(item);
        }

    }

    /**
     * ????????????
     * @return
     */
    private OrderCreateTo createOrder() {

        OrderCreateTo orderCreateTo = new OrderCreateTo();

        // ??????????????? mp?????????
        // ?????? ID = Time + ID
        // *<p>?????????????????????????????? ID</p>
        String orderSn = IdWorker.getTimeId();
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderSn(orderSn);

        MemberRespVo memberRespVo = LoginUserInterceptor.threadLocal.get();
        orderEntity.setMemberId(memberRespVo.getId());
        orderEntity.setReceiverName(memberRespVo.getNickname());

        orderEntity.setStatus(OrderStatusEnum.CREATE_NEW.getCode());
        orderEntity.setAutoConfirmDay(7);

        // ?????????????????????
        OrderSubmitVo orderSubmitVo = orderSubmitVoThreadLocal.get();


        // ???????????????????????????
        List<OrderItemEntity> orderItemEntities = buidOrderItems(orderSn);

        // ???????????????????????????
        computerPrice(orderEntity,orderItemEntities);

        orderCreateTo.setOrderEntity(orderEntity);

        orderCreateTo.setItems(orderItemEntities);

        return orderCreateTo;
    }

    /**
     * ???????????????????????????
     * @param orderEntity
     * @param orderItemEntities
     */
    private void computerPrice(OrderEntity orderEntity, List<OrderItemEntity> orderItemEntities) {
        BigDecimal totalPrice = new BigDecimal("0.0");
        BigDecimal totalGiftIntegration = new BigDecimal("0.0");
        BigDecimal totalGiftGrowth = new BigDecimal("0.0");

        for (OrderItemEntity itemEntity : orderItemEntities) {
            BigDecimal realAmount = itemEntity.getRealAmount();
            totalPrice = totalPrice.add(realAmount);

            totalGiftIntegration = totalGiftIntegration.add(new BigDecimal(itemEntity.getGiftIntegration().toString()));
            totalGiftGrowth = totalGiftGrowth.add(new BigDecimal(itemEntity.getGiftGrowth()));
        }

        orderEntity.setTotalAmount(totalPrice);
        orderEntity.setPayAmount(totalPrice);

        orderEntity.setIntegration(totalGiftIntegration.intValue());
        orderEntity.setGrowth(totalGiftGrowth.intValue());
    }

    /**
     * ???????????????????????????
     * @return
     * @param orderSn
     */
    private List<OrderItemEntity> buidOrderItems(String orderSn) {
        // ??????????????????????????????
        List<OrderItemVo> currentUserCartItems = cartFeignService.currentUserCartItems();
        if (currentUserCartItems != null && currentUserCartItems.size() > 0) {
            List<OrderItemEntity> orderItemEntities = currentUserCartItems.stream().map(cartItem -> {
                OrderItemEntity orderItemEntity = buidOrderItem(cartItem);

                orderItemEntity.setOrderSn(orderSn);
                return orderItemEntity;
            }).collect(Collectors.toList());
            return orderItemEntities;
        }
        return null;
    }

    /**
     * ???????????????????????????
     * @param cartItem
     * @return
     */
    private OrderItemEntity buidOrderItem(OrderItemVo cartItem) {

        OrderItemEntity orderItemEntity = new OrderItemEntity();

        // ??????spu??????
        R r = productFeignService.getSpuInfoBySkuId(cartItem.getSkuId());
        SpuInfoVo spuInfoVo = r.getData(new TypeReference<SpuInfoVo>() {
        });
        orderItemEntity.setSpuId(spuInfoVo.getId());
        orderItemEntity.setSpuBrand(spuInfoVo.getBrandId().toString());
        orderItemEntity.setSpuName(spuInfoVo.getSpuName());
        orderItemEntity.setCategoryId(spuInfoVo.getCatalogId());

        // sku??????
        orderItemEntity.setSkuId(cartItem.getSkuId());
        orderItemEntity.setSkuName(cartItem.getTitle());
        orderItemEntity.setSkuPic(cartItem.getImage());
        orderItemEntity.setSkuPrice(cartItem.getPrice());
        String skuAttr = StringUtils.collectionToDelimitedString(cartItem.getSkuAttr(), ";");
        orderItemEntity.setSkuAttrsVals(skuAttr);
        orderItemEntity.setSkuQuantity(cartItem.getCount());

        // ????????????????????????
        // TODO ????????????
        int giftGrowth = (cartItem.getTotalPrice().intValue()) / 10;
        orderItemEntity.setGiftGrowth(giftGrowth);
        orderItemEntity.setGiftIntegration(giftGrowth);
        // ???????????????????????????????????????
        // TODO ????????????????????????
        orderItemEntity.setRealAmount(orderItemEntity.getSkuPrice().multiply(new BigDecimal(orderItemEntity.getSkuQuantity().toString())));

        return orderItemEntity;

    }


    /**
     * ????????????????????????????????????
     * @param orderSn
     * @return
     */
    @Override
    public OrderEntity getOrderByOrderSn(String orderSn) {
        return this.getOne(new QueryWrapper<OrderEntity>().eq("order_sn", orderSn));
    }

    /**
     * ????????????
     * @param entity
     */
    @Override
    public void closeOrder(OrderEntity entity) {
        OrderEntity orderEntity = this.getById(entity.getId());
        if (orderEntity.getStatus() == OrderStatusEnum.CREATE_NEW.getCode()) {
            // ??????
            OrderEntity update = new OrderEntity();
            update.setId(entity.getId());
            update.setStatus(OrderStatusEnum.CANCLED.getCode());
            this.updateById(update);

            // ???????????????MQ
            OrderTo orderTo = new OrderTo();
            BeanUtils.copyProperties(orderEntity, orderTo);

            // ??????????????????????????????????????????????????????????????????????????????????????????????????????
            // ???????????????????????????????????????????????????
            try {
                rabbitTemplate.convertAndSend("order-event-exchange", "order.release.other", orderTo);
            } catch (Exception e) {

            }
        }
    }

    /**
     * ?????????????????????????????????
     * @param orderSn
     * @return
     */
    @Override
    public PayVo getOrderPayInfo(String orderSn) {

        OrderEntity order = getOrderByOrderSn(orderSn);

        PayVo payVo = new PayVo();
        // ??????????????????????????????2????????????
        // .setScale(2, BigDecimal.ROUND_UP) ???????????????????????????????????????
        String totalAmount = order.getPayAmount().setScale(2, BigDecimal.ROUND_UP).toString();
        payVo.setTotal_amount(totalAmount);
        payVo.setOut_trade_no(orderSn);
        List<OrderItemEntity> orderItemList = orderItemService.list(new QueryWrapper<OrderItemEntity>().eq("order_sn", orderSn));
        OrderItemEntity orderItem = orderItemList.get(0);
        payVo.setSubject(orderItem.getSkuName());
        payVo.setBody(orderItem.getSkuAttrsVals());

        return payVo;
    }


    /**
     * ???????????????????????????
     * @param params
     * @return
     */
    @Override
    public PageUtils queryPageWhtiItem(Map<String, Object> params) {

        MemberRespVo memberRespVo = LoginUserInterceptor.threadLocal.get();

        IPage<OrderEntity> page = this.page(
                new Query<OrderEntity>().getPage(params),
                new QueryWrapper<OrderEntity>().eq("member_id", memberRespVo.getId()).orderByDesc("id")
        );

        List<OrderEntity> orderEntityList = page.getRecords().stream().map(orderEntity -> {

            List<OrderItemEntity> items = orderItemService.list(new QueryWrapper<OrderItemEntity>().eq("order_sn", orderEntity.getOrderSn()));
            orderEntity.setOrderItemEntityList(items);
            return orderEntity;
        }).collect(Collectors.toList());

        page.setRecords(orderEntityList);

        return new PageUtils(page);

    }

    /**
     * ??????????????????????????????
     * @param payAsyncVo ????????????????????????
     * @return
     */
    @Override
    public String handlePayResult(PayAsyncVo payAsyncVo) {

        // 1???????????????????????????
        PaymentInfoEntity paymentInfoEntity = new PaymentInfoEntity();
        paymentInfoEntity.setAlipayTradeNo(payAsyncVo.getTrade_no());
        paymentInfoEntity.setOrderSn(payAsyncVo.getOut_trade_no());
        paymentInfoEntity.setPaymentStatus(payAsyncVo.getTrade_status());
        paymentInfoEntity.setCallbackTime(payAsyncVo.getNotify_time());
        paymentInfoService.save(paymentInfoEntity);

        // 2???????????????????????????
        if (payAsyncVo.getTrade_status().equals("TRADE_SUCCESS") || payAsyncVo.getTrade_status().equals("TRADE_FINISHED")) {
            // ????????????
            String orderSn = payAsyncVo.getOut_trade_no();
            this.baseMapper.updateOrderStatus(orderSn, OrderStatusEnum.PAYED.getCode());
        }
        return "success";
    }

    /**
     * ????????????????????????
     * TODO ?????????????????????
     * @param secKillOrderTo
     */
    @Override
    public void createSecKillOrder(SecKillOrderTo secKillOrderTo) {
        // TODO ??????????????????

        // ??????????????????
        OrderEntity orderEntity = new OrderEntity();
        orderEntity.setOrderSn(secKillOrderTo.getOrderSn());
        orderEntity.setMemberId(secKillOrderTo.getMemberId());

        orderEntity.setStatus(OrderStatusEnum.CREATE_NEW.getCode());
        BigDecimal totalPrice = secKillOrderTo.getSeckillPrice().multiply(new BigDecimal(secKillOrderTo.getNum() + ""));
        orderEntity.setPayAmount(totalPrice);

        this.save(orderEntity);

        OrderItemEntity orderItemEntity = new OrderItemEntity();
        orderItemEntity.setOrderSn(secKillOrderTo.getOrderSn());
        orderItemEntity.setRealAmount(totalPrice);
        orderItemEntity.setSkuQuantity(secKillOrderTo.getNum());

        orderItemService.save(orderItemEntity);


    }
}