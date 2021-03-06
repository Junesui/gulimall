package com.atguigu.gulimall.product;

import com.atguigu.gulimall.product.dao.AttrGroupDao;
import com.atguigu.gulimall.product.dao.SkuSaleAttrValueDao;
import com.atguigu.gulimall.product.entity.BrandEntity;
import com.atguigu.gulimall.product.service.BrandService;
import com.atguigu.gulimall.product.service.CategoryService;
import com.atguigu.gulimall.product.vo.SkuItemSaleAttrVo;
import com.atguigu.gulimall.product.vo.SpuItemAttrGroupVo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

@RunWith(SpringRunner.class)
@SpringBootTest
public class GulimallProductApplicationTests {

    @Autowired
    private BrandService brandService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    RedisTemplate<Object,Object> redisTemplate;

    @Autowired
    RedissonClient redissonClient;

    @Autowired
    AttrGroupDao attrGroupDao;

    @Autowired
    SkuSaleAttrValueDao skuSaleAttrValueDao;


    @Test
    public void getSaleAttrsBySpuId() {

        List<SkuItemSaleAttrVo> saleAttrsBySpuId = skuSaleAttrValueDao.getSaleAttrsBySpuId(2l);

        for (SkuItemSaleAttrVo skuItemSaleAttrVo : saleAttrsBySpuId) {
            System.out.println(skuItemSaleAttrVo);
        }

    }


    @Test
    public void getAttrGroupWithAttrsBySpuIdTest() {

        List<SpuItemAttrGroupVo> attrGroupWithAttrsBySpuId = attrGroupDao.getAttrGroupWithAttrsBySpuId(225L, 2L);

        for (SpuItemAttrGroupVo spuItemAttrGroupVo : attrGroupWithAttrsBySpuId) {
            System.out.println(spuItemAttrGroupVo);
        }

    }

    @Test
    public void redissonTest() {
        System.out.println(redissonClient);
    }

    @Test
    public void stringRedisTemplateTest() {

        stringRedisTemplate.opsForValue().set("hello","world");
        System.out.println("???????????????");

        String hello = stringRedisTemplate.opsForValue().get("hello");
        System.out.println("?????????????????????" +  hello);

    }

    @Test
    public void redisTemplateTest() {

        BrandEntity brand = new BrandEntity();
        brand.setName("?????????????????????redis");

        redisTemplate.opsForValue().set("brand", brand);
        System.out.println("???????????????");

        BrandEntity obj = (BrandEntity) redisTemplate.opsForValue().get("brand");
        System.out.println("?????????????????????" +  obj.getName());

    }

    @Test
    public void categoryPathTest() {

        Long[] catelogPath = categoryService.findCatelogPath( 225L);
        for (Long aLong : catelogPath) {
            System.out.println(aLong);
        }
    }

//    @Autowired
//    OSSClient ossClient;
//
//    @Test
//    public void ossTest() throws FileNotFoundException {
//        // Endpoint????????????????????????Region???????????????????????????
////        String endpoint = "oss-cn-beijing.aliyuncs.com";
//        // ?????????AccessKey?????????API????????????????????????????????????????????????????????????????????????RAM???????????????API????????????????????????????????? https://ram.console.aliyun.com ?????????
////        String accessKeyId = "LTAI4Fec4n99HjGECZGjxVx8";
////        String accessKeySecret = "By1KnhZRjUR8imapMzYTqR6m0F1s1n";
//
//        // ??????OSSClient?????????
////        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
//
//        // ??????????????????
//        InputStream inputStream = new FileInputStream("/Users/crx/Downloads/2020??????/1-???????????????_???????????????/docs/pics/e3284f319e256a5d.jpg");
//        ossClient.putObject("gulimall-lubancantfly", "e3284f319e256a5d.jpg", inputStream);
//
//        // ??????OSSClient???
//        ossClient.shutdown();
//        System.out.println("????????????");
//    }

    @Test
    public void contextLoads() {

        BrandEntity brandEntity1 = new BrandEntity();
        brandEntity1.setName("??????");
        brandService.save(brandEntity1);

        List<BrandEntity> brands = brandService.list();
        brands.forEach( (brandEntity -> {
            System.out.println(brandEntity);
        }));
    }

}
