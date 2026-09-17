package com.duoduo.trade.controller;

import com.duoduo.common.api.ApiResponse;
import com.duoduo.trade.dto.OrderDtos.PayResult;
import com.duoduo.trade.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 支付单接口：渠道回调、沙箱收银台、支付单查询。
 *
 * <p><b>访问控制分层</b>（谁都能调、谁不能调，逐个说清）：
 * <ul>
 *   <li>{@code POST /api/payments/callback/{channel}} —— <b>公开</b>。
 *       渠道回调来自渠道服务器，没有我们的登录态；安全性完全靠
 *       <b>渠道验签 + 金额核对 + CAS 幂等</b>三件事，不靠登录。
 *       网关白名单放行（见 gateway 的 security.whitelist）。</li>
 *   <li>{@code POST /api/payments/sandbox/{payNo}} —— <b>公开</b>。
 *       现实中「扫了二维码的人就是付款人」，收银台页面在扫码设备上打开，
 *       那台设备上没有买家的登录态。仅限 SANDBOX 渠道，金额一律取库内值。</li>
 *   <li>{@code GET /api/payments/{payNo}} —— <b>需登录</b>。买家轮询自己的支付单；
 *       不是自己的支付单一律 404，不暴露存在性。</li>
 *   <li>{@code GET /sandbox/pay/{payNo}} —— <b>公开</b>。沙箱收银台页面（服务端渲染）。</li>
 * </ul>
 */
@RestController
public class PaymentController {

    private final OrderService orderService;

    public PaymentController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 渠道回调入口（沙箱与真实渠道共用）。
     *
     * <p>读的是<b>原始报文体</b>而不是反序列化对象：签名是对原始字节算的，
     * 先交给 Jackson 反序列化再拼回去验签，任何字段顺序 / 空白差异都会让验签必然失败。
     */
    @PostMapping("/api/payments/callback/{channel}")
    public ApiResponse<PayResult> callback(@PathVariable("channel") String channel,
                                           @RequestBody String rawBody,
                                           @RequestHeader Map<String, String> headers) {
        return ApiResponse.ok(orderService.handleChannelCallback(channel, rawBody, headers));
    }

    /** 沙箱收银台「模拟支付」动作（走同一套验签与入账路径） */
    @PostMapping("/api/payments/sandbox/{payNo}")
    public ApiResponse<PayResult> sandboxPay(@PathVariable("payNo") String payNo) {
        return ApiResponse.ok(orderService.sandboxPay(payNo));
    }

    /** 查询自己的支付单（扫码后前端轮询用） */
    @GetMapping("/api/payments/{payNo}")
    public ApiResponse<PayResult> get(@PathVariable("payNo") String payNo) {
        return ApiResponse.ok(orderService.getPaymentView(payNo));
    }

    private static final MediaType TEXT_HTML_UTF8 =
            MediaType.parseMediaType("text/html;charset=UTF-8");

    /**
     * 沙箱收银台页面（服务端渲染，扫码设备直接打开）。
     *
     * <p>页面里的「支付」按钮 POST 同源的 {@code /api/payments/sandbox/{payNo}}，
     * 成功后展示结果 —— 整个过程不需要登录态，与真实扫码支付的体验一致。
     *
     * <p>Content-Type 显式声明 charset：页面是纯中文 HTML，不声明的话
     * 部分 HTTP 客户端（如 PowerShell 5.1 的 Invoke-WebRequest）会按 ISO-8859-1
     * 解码，中文全部变乱码；浏览器虽然会读 meta charset 兜底，但声明在头里才是一致行为。
     */
    @GetMapping("/sandbox/pay/{payNo}")
    public ResponseEntity<String> cashier(@PathVariable("payNo") String payNo) {
        PayResult result;
        try {
            // 收银台只展示公开可见的信息（金额 / 单号 / 状态），复用沙箱支付的校验与视图装配
            result = orderService.sandboxPayPreview(payNo);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(TEXT_HTML_UTF8)
                    .body("<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
                            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                            + "<title>支付单不存在</title></head>"
                            + "<body style=\"font-family:sans-serif;text-align:center;padding-top:60px;\">"
                            + "<h2>支付单不存在或已失效</h2>"
                            + "<p style=\"color:#888;\">请回到商城重新发起支付</p></body></html>");
        }
        String yuan = String.format("%.2f", result.amount() / 100.0);
        String html = """
                <!DOCTYPE html>
                <html lang="zh">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>多多宠物商店 · 沙箱支付</title>
                <style>
                  body{font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#f5f6f8;
                       margin:0;display:flex;justify-content:center;min-height:100vh}
                  .card{background:#fff;width:100%%;max-width:420px;margin:40px 16px;border-radius:16px;
                        padding:32px 24px;box-shadow:0 4px 24px rgba(0,0,0,.08);text-align:center}
                  .brand{color:#0abf6b;font-weight:700;letter-spacing:2px}
                  .amount{font-size:44px;font-weight:700;margin:24px 0 4px}
                  .amount small{font-size:18px;font-weight:400}
                  .meta{color:#888;font-size:13px;line-height:1.9}
                  .btn{width:100%%;border:0;border-radius:24px;background:#0abf6b;color:#fff;font-size:17px;
                       padding:14px 0;margin-top:28px;cursor:pointer}
                  .btn:disabled{background:#bbb;cursor:not-allowed}
                  .note{margin-top:16px;font-size:12px;color:#aaa}
                  .result{display:none;margin-top:28px;padding:16px;border-radius:12px;font-size:15px}
                  .ok{background:#e8f8f0;color:#0a8f52}
                  .fail{background:#fdeaea;color:#c0392b}
                </style>
                </head>
                <body>
                <div class="card">
                  <div class="brand">多多宠物商店 · 沙箱支付</div>
                  <div class="amount"><small>￥</small>%s</div>
                  <div class="meta">
                    支付单号 %s<br>
                    订单号 %s<br>
                    状态 %s
                  </div>
                  <button class="btn" id="payBtn" onclick="doPay()">模拟扫码支付</button>
                  <div class="result" id="result"></div>
                  <div class="note">这是沙箱渠道：点击即模拟「在渠道侧完成付款」，<br>
                    后端会走与真实渠道完全相同的验签与入账路径。</div>
                </div>
                <script>
                function doPay(){
                  var btn=document.getElementById('payBtn');
                  var box=document.getElementById('result');
                  btn.disabled=true;btn.textContent='支付中…';
                  fetch('/api/payments/sandbox/%s',{method:'POST'})
                    .then(function(r){return r.json().then(function(j){return {ok:r.ok,data:j};});})
                    .then(function(r){
                      var d=r.data&&r.data.data;
                      if(r.ok&&d&&d.payStatus==='SUCCESS'){
                        box.className='result ok';
                        box.textContent='支付成功 ✓（流水号 '+(d.channelTradeNo||'')+'）';
                        btn.textContent='已支付';
                      }else{
                        box.className='result fail';
                        box.textContent='支付未完成：'+((r.data&&r.data.error&&r.data.error.message)||'未知原因');
                        btn.disabled=false;btn.textContent='重试';
                      }
                    })
                    .catch(function(){
                      box.className='result fail';box.textContent='网络异常，请重试';
                      btn.disabled=false;btn.textContent='重试';
                    });
                }
                </script>
                </body>
                </html>
                """.formatted(yuan, result.payNo(), result.orderNo(), result.payStatus(), result.payNo());
        return ResponseEntity.ok()
                .contentType(TEXT_HTML_UTF8)
                .body(html);
    }
}
