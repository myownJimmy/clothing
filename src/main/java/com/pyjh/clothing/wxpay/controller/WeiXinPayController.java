package com.pyjh.clothing.wxpay.controller;

import java.util.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.alibaba.fastjson.JSON;
import com.pyjh.clothing.entity.PageData;
import com.pyjh.clothing.service.*;
import com.pyjh.clothing.util.CommonUtil;
import com.pyjh.clothing.util.DateUtil;
import com.pyjh.clothing.util.OrderNoUtil;
import com.pyjh.clothing.wxpay.hander.PrepayIdRequestHandler;
import com.pyjh.clothing.wxpay.util.ConstantUtil;
import com.pyjh.clothing.wxpay.util.MD5Util;
import com.pyjh.clothing.wxpay.util.WXUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import springfox.documentation.annotations.ApiIgnore;

@Controller("WXOrderPay")
@RequestMapping("/wx/pay")
@Api(value = "orderpay",description = "购物车下单，立即下单")
public class WeiXinPayController {

    @Resource
    MemberService memberService;

    @Resource
    ProductService productService;

    @Resource
    OrderService orderService;

    @Resource
    ShopCartService shopCartService;
    @Resource
    CouponsService couponsService;// 优惠券
    @Resource
    ExchangeService exchangeService;
    @Resource
    Product_order_detailService product_order_detailService;
    @Resource
    CustomService customService;

    /**
     * 1.生成订单； 2.并且生成订单详情； 3.获取生成预支付订单的请求类 4.若获取prepayid，封装订单数据
     */
    @RequestMapping(value = "/createOrder", method = {RequestMethod.POST,
            RequestMethod.GET}, produces = "application/json;charset=UTF-8")
    @ResponseBody
    @ApiOperation("生成订单")
    public Map<String, Object> doWeinXinRequest(HttpServletRequest request, HttpServletResponse response,
                                                @RequestParam(value = "openid", required = false) String openid,
                                                @RequestParam(value = "member_id", required = false) Integer member_id,
                                                @RequestParam(value = "address_id", required = false) Integer address_id,
                                                @RequestParam(value = "payWay", required = false) String pay_way,
                                                @RequestParam(value = "BuyWay", required = false) String BuyWay,
                                                @RequestParam(value = "messages", required = false) String messages,
                                                @RequestParam(value = "product_ids", required = false) String product_idStr,
                                                @RequestParam(value = "amounts", required = false) String amountStr,
                                                @RequestParam(value = "ec_id", required = false) Integer ec_id,
                                                Integer franchiseeId,
                                                @RequestParam(value = "shopCartId", required = false) String shopCartId,
                                                @RequestParam(value = "skuId", required = false) String skuId) throws Exception {
        response.setHeader("Access-Control-Allow-Origin", "*");
        System.out.println(",openid:" + openid +
                ",\nmember_id:" + member_id + ",messages:" + messages +
                ",address_id:" + address_id +
                ",\npay_way:" + pay_way + ",BuyWay:" + BuyWay +
                ",\nproduct_idStr:" + product_idStr + ",amountStr:" + amountStr +
                ",\nec_id:" + ec_id + ",shopCartId:" + shopCartId+ ",skuId:" + skuId + "\n");
        Map<String, Object> model = new HashMap<String, Object>();
        if (CommonUtil.paramIsNull(openid) || CommonUtil.paramIsNull(member_id)
                || CommonUtil.paramIsNull(address_id) || CommonUtil.paramIsNull(pay_way)
                || CommonUtil.paramIsNull(BuyWay) || CommonUtil.paramIsNull(product_idStr)
                || CommonUtil.paramIsNull(amountStr)) {
            model.put("msg", "参数为空");
            model.put("status", "402");
            return model;
        }
        if (product_idStr.length() > 0) {
            // 用“，”分割
            String[] product_ids = product_idStr.split(",");
            String[] amounts = amountStr.split(",");
            Map<String, Object> resultMap = new LinkedHashMap<String, Object>();
            String out_trade_no = "";
//            try {
            // 支付账户
            String payMember = memberService.getMemberId(member_id).getString("mobile");
            // 订单
            PageData pOrder = new PageData();
            double order_price = 0;
            // 随机字符串，不长于32位。推荐随机数生成算法
            String nonce_str = WXUtil.getNonceStr();
            // 3.1封装数据
            double[] prices = new double[amounts.length];
            String[] names = new String[amounts.length];
       //     String[] productIcon = new String[amounts.length];
            for (int i = 0; i < amounts.length; i++) {
                PageData pProduct = new PageData();
                pProduct.put("productId", Integer.parseInt(product_ids[i]));
                PageData product = productService.getProductId(Integer.parseInt(product_ids[i]));

                System.out.println("========product=" + JSON.toJSONString(product));
                prices[i] = (Double) product.get("product_price");
              /*  names[i] = (String) product.get("product_name");
                productIcon[i] = (String) product.get("icon_url");*/
                // 单个产品总价，遍历之后就是所有总价
                order_price = order_price + (Double) product.get("product_price") * Integer.parseInt(amounts[i]);
            }

            // 生成订单号，全数据库唯一,不唯一的时候，支付回调 例：201807021607465eaa88330
            System.out.println(">>>>>>>>>>>>>>>.." + order_price);
            out_trade_no = OrderNoUtil.getOrderNo();
            PageData pOne = new PageData();
            while (true) {
                pOne.put("orderCode", out_trade_no);
                List<PageData> order = orderService.getOrder(pOne);
                // 为空时跳出循环
                System.out.println("ssssssssssssss" + order);
                if (order.isEmpty()) {
                    break;
                }
                out_trade_no = OrderNoUtil.getOrderNo();
            }

            // 生成订单
            System.out.println(">>>>>>>>>>>>>>>.." + out_trade_no);
            pOrder = new PageData();

            /**
             *  1.生成价格时，判断是否有优惠券
             *  2.判断优惠券类型（代金，打折）
             *  3.生成折扣后的金额
             */
            PageData pCoupons = new PageData();
            if (CommonUtil.isEmptyObject(ec_id)){
                pCoupons.put("member_id", member_id);
                pCoupons.put("ec_id", ec_id);
                pCoupons = couponsService.listExchange(pCoupons).get(0);
                System.out.println("================order_priceOld===" + order_price);
                System.out.println("type_id=" + pCoupons.getInteger("type_id"));
                if (pCoupons.getInteger("type_id") == 3) {
                    // 优惠券
                    order_price = order_price - pCoupons.getDouble("discount_amount");
                    pOrder.put("ec_id",ec_id);
                }
            }
            System.out.println("================order_priceNew===" + order_price);

            // 送货上们就加地址
            if ("送货上门".equals(BuyWay)) {
                pOrder.put("addressId", address_id);
            } else {
                pOrder.put("addressId", 0);
            }
            pOrder.put("memberId", member_id);
            pOrder.put("payWay", pay_way);
            pOrder.put("orderPrice", order_price);
            pOrder.put("status", 1);
            pOrder.put("payAccount", payMember);
            pOrder.put("orderCode", out_trade_no);
            pOrder.put("orderNote", messages);
            pOrder.put("create_time",DateUtil.getTime());
            orderService.addOrder(pOrder);
            Integer orderId = orderService.getMaxOrderId();
            PageData psku = new PageData();
            for (int i = 0; i < amounts.length; i++) {
                String[] shopCartIds = product_idStr.split(",");
                psku.put("cartId", Integer.valueOf(shopCartIds[i]));
            }
            psku.put("orderId", orderId);
            String[] sku = skuId.split(",");

            // 订单详情
            PageData pDetail = new PageData();
            for (int i = 0; i < amounts.length; i++) {
                pDetail = new PageData();
                pDetail.put("orderCode", out_trade_no);
                pDetail.put("productId", Integer.parseInt(product_ids[i]));
                pDetail.put("productName", names[i]);
                pDetail.put("price", prices[i]);
                pDetail.put("create_time", DateUtil.getTime());
                pDetail.put("amount", Integer.parseInt(amounts[i]));
                pCoupons.put("status",3);
                // pDetail.put("productIcon", Integer.parseInt(productIcon[i]));
              int row= orderService.addOrderDetail(pDetail);
              exchangeService.updateExchange(pCoupons);
               if (row>0) {
                   System.out.println("198:row="+row);
                   psku.put("orderId", pDetail.getInteger("order_detail_id"));
                   if (shopCartId != null) {
                       psku.put("cartId", shopCartId);
                      int rowscs = shopCartService.updateShopChartSku(psku);
                       System.out.println("202:rowscs="+rowscs);
                   } else {
                       for (int j = 0; sku.length > j; j++) {
                           // 新增订单规格
                           psku.put("skuId", Integer.parseInt(sku[j]));
                           shopCartService.addShopOrderSku(psku);
                         // customService.addCustomByMember(psku);

                       }
                   }
               }
            }

            // ---------------2 生成订单号 开始------------------------
            String timestamp = WXUtil.getTimeStamp(); // 超时时间
            // 3.2---------------------------------------------- *****
            // 统一下单开始,获取商户的常量 *****
            // -----------------------------------------------------------
            // 3.获取生成预支付订单的请求类
            PrepayIdRequestHandler prepayReqHandler = new PrepayIdRequestHandler(request, response);
            prepayReqHandler.setParameter("appid", ConstantUtil.APP_ID); // 平台应用appId
            prepayReqHandler.setParameter("mch_id", ConstantUtil.MCH_ID); // 商户号
            prepayReqHandler.setParameter("nonce_str", nonce_str); // 随机字符串
            prepayReqHandler.setParameter("body", ConstantUtil.BUY_BODY); // 商品描述
            prepayReqHandler.setParameter("out_trade_no", out_trade_no); // 订单号
            // 订单价格,1234.5678-->"123456" ,单位为“分”
            prepayReqHandler.setParameter("total_fee", String.valueOf(order_price * 100).split("\\.")[0]);
            // 获取客户端ip，使用ip时可以，域名时为本地
            prepayReqHandler.setParameter("spbill_create_ip", request.getRemoteAddr());
            prepayReqHandler.setParameter("notify_url", ConstantUtil.NOTIFY_URL); // 回调通知
            prepayReqHandler.setParameter("trade_type", "JSAPI"); // 支付类型
            prepayReqHandler.setParameter("openid", openid); // 用户的唯一标识
            // prepayReqHandler.setParameter("time_start", timestamp); //时间戳
            prepayReqHandler.setGateUrl(ConstantUtil.GATEURL); // 设置预支付id的接口url

            // 3.3
            // 注意签名（sign）的生成方式，具体见官方文档（传参都要参与生成签名，且参数名按照字典序排序，最后接上APP_KEY,转化成大写）

            prepayReqHandler.setParameter("sign", prepayReqHandler.createMD5Sign(ConstantUtil.APP_KEY)); // sign
            // 签名
            // 3.4 提交预支付,获取prepayid
            String prepayid = prepayReqHandler.sendPrepay();
            // ---------------------------------------------- ***** 统一下单 结束
            // *****
            // --------------------------------------------------------------

            // 3.5 若获取prepayid成功，将相关信息返回客户端
            if (prepayid != null && !prepayid.equals("")) {

                // ---------------4.封装订单数据开始 ------------------------
                //************ 一定要注意参数的顺序 *****************
                String signs = "appId=" + ConstantUtil.APP_ID + "&nonceStr=" + nonce_str + "&package=prepay_id="
                        + prepayid + "&signType=MD5" + "&timeStamp=" + timestamp + "&key=" + ConstantUtil.APP_KEY;

                resultMap.put("appid", ConstantUtil.APP_ID);
                resultMap.put("partnerid", ConstantUtil.MCH_ID); // 商家id
                resultMap.put("prepayid", prepayid); // 预支付id
                resultMap.put("package", "Sign=WXPay"); // 固定常量
                resultMap.put("noncestr", nonce_str); // 与请求prepayId时值一致
                resultMap.put("total_fee", String.valueOf(order_price * 100).split("\\.")[0]); // 订单总金额，单位为分
                resultMap.put("timestamp", timestamp); // 等于请求prepayId时的time_start
                resultMap.put("spbill_create_ip", request.getRemoteAddr()); // 获取客户端ip
                resultMap.put("sign", MD5Util.MD5Encode(signs, "utf8").toUpperCase());// 签名方式与上面类似
                model.put("orderNum", out_trade_no);
                model.put("resultMap", resultMap);
                model.put("msg", "获取prepayid成功,生成订单成功");
                model.put("status", "200");
                if (CommonUtil.isEmptyObject(model)) {
                    shopCartService.delete(pDetail);
                }

            } else {
                model.put("msg", "获取prepayid失败");
                model.put("status", "210");
            }

        } else {
            model.put("msg", "订单生成失败");
            model.put("status", "444");
        }
        return model;
    }


}
