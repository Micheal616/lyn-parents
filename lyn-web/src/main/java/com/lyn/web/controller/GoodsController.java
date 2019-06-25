package com.lyn.web.controller;

import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.fastjson.JSONObject;
import com.lyn.common.cache.CacheService;
import com.lyn.common.exception.DescribeException;
import com.lyn.common.exception.ExceptionEnum;
import com.lyn.common.utils.FileUtils;
import com.lyn.common.utils.RequestStr;
import com.lyn.common.utils.ResultUtils;
import com.lyn.goods.api.constants.GoodsConstant;
import com.lyn.goods.api.entity.GoodsInfo;
import com.lyn.goods.api.service.GoodsService;
import com.lyn.sys.api.entity.UserInfo;
import com.lyn.web.constants.WebConstant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Random;

/**
 * <p>请添加描述信息</p>
 *
 * @author lft
 * @version 1.0
 * @date 2019/6/14 0014
 * @since jdk1.8
 */
@Controller
@RestController
@RequestMapping("/api/goods/")
public class GoodsController {

    @Reference(version = "1.0.0")
    private GoodsService goodsService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private CacheService cacheService;

    @RequestMapping(value = "add", method = RequestMethod.POST)
    public Object addGoodsIfo(HttpServletRequest request) throws Exception {
        UserInfo loginUser = (UserInfo) request.getAttribute(WebConstant.LOGIN_SESSION);
        String requestStr = RequestStr.getRequestStr(request);
        JSONObject jsonObj = JSONObject.parseObject(requestStr);
        if(StringUtils.isEmpty(jsonObj.getString("goodsName"))
                || StringUtils.isEmpty(jsonObj.getString("goodsCover"))
                || StringUtils.isEmpty(jsonObj.getString("goodsDetail"))){
            throw new DescribeException(ExceptionEnum.PARAM_ERROR);
        }
        GoodsInfo goodsInfo = new GoodsInfo();
        goodsInfo.setGoodsName(jsonObj.getString("goodsName"));
        goodsInfo.setGoodsCover(FileUtils.toShortUrl(jsonObj.getString("goodsCover")));
        goodsInfo.setGoodsCover(FileUtils.toShortUrl(jsonObj.getString("goodsDetail")));
        goodsInfo.setCreator(loginUser.getUserId());
        goodsService.addGoodsInfo(goodsInfo);
        return ResultUtils.success("ok");
    }

    @RequestMapping(value = "detail", method = RequestMethod.POST)
    public Object detail(HttpServletRequest request) throws Exception {
        String requestStr = RequestStr.getRequestStr(request);
        JSONObject jsonObj = JSONObject.parseObject(requestStr);
        if(StringUtils.isEmpty(jsonObj.getString("goodsId"))){
            throw new DescribeException(ExceptionEnum.PARAM_ERROR);
        }
        int goodsId = jsonObj.getInteger("goodsId");
        if(goodsId <= 0){
            //当id小于等于0时,我们认为是异常操作
            throw new DescribeException(ExceptionEnum.OPERATE_ERROR);
        }
        GoodsInfo goods = (GoodsInfo) cacheService.getCacheByKey("lyn_goods:"+goodsId);
        if(null == goods){
            String key = "lyn_goods_detail_lock";
            if(cacheService.lock(key,"0")) {
                goods = this.getGoodsInfo(goodsId);
                cacheService.unLock(key);
            } else {
                goods = this.getGoodsInfo(goodsId);
            }
        }
        kafkaTemplate.send(GoodsConstant.KAFKA_STATISTICS_GOODS_BYID,String.valueOf(goodsId));
        return ResultUtils.success(goods);
    }

    /**
     * 获取商品信息
     * @param goodsId
     * @return
     * @throws Exception
     */
    private GoodsInfo getGoodsInfo(int goodsId) throws Exception{
        GoodsInfo goods = goodsService.findGoodsInfoByPrimary(goodsId);
        if (goods != null) {
            goods.setGoodsCover(StringUtils.isEmpty(goods.getGoodsCover())?null: FileUtils.toFullUrl(goods.getGoodsCover()));
            Random random = new Random();
            if (goods.getIsHotSell()) {
                //热门商品
                long expire = 3600 + random.nextInt(3600);
                cacheService.setCacheToRedis("lyn_goods:" + goodsId, goods, expire);
            } else {
                //冷门商品
                long expire = 600 + random.nextInt(600);
                cacheService.setCacheToRedis("lyn_goods:" + goodsId, goods, expire);
            }
        } else {
            cacheService.setCacheToRedis("lyn_goods:" + goodsId, goods, 60);
        }
        return goods;
    }
}
