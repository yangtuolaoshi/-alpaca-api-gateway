package icu.ytlsnb.alpacaapigateway.filter;

import cn.hutool.core.util.StrUtil;
import icu.ytlsnb.alpacaapiclientsdk.utils.SignUtils;
import icu.ytlsnb.dubbo.InterfaceInfoDubbo;
import icu.ytlsnb.dubbo.model.pojo.InterfaceInfo;
import icu.ytlsnb.dubbo.model.pojo.User;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Component
@Slf4j
public class ProjectFilter implements GlobalFilter, Ordered {
//    private final List<String> WHITE_LIST = Arrays.asList("127.0.0.1");

    @DubboReference(scope = "remote")
    private InterfaceInfoDubbo interfaceInfoDubbo;

    public static final String HOST = "http://localhost:6661";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        System.out.println(interfaceInfoDubbo.getName("nihao"));
        // 2. 请求日志
        ServerHttpRequest request = exchange.getRequest();
        log.info("ID: {}", request.getId());
        String path = HOST + request.getPath().value();
        log.info("Path: {}", path);
        HttpMethod method = request.getMethod();
        log.info("Method: {}", method);
        log.info("Params: {}", request.getQueryParams());
        String remoteAddress = request.getRemoteAddress().getHostString();
        log.info("Remote Address: {}", remoteAddress);
        log.info("-------------------------------");
//        // 3. 黑白名单
//        if (!WHITE_LIST.contains(remoteAddress)) {
//            return exchange.getResponse().setComplete();
//        }
        // 4. 鉴权
        HttpHeaders headers = request.getHeaders();
        String accessKey = headers.getFirst("accessKey");
        String nonce = headers.getFirst("nonce");
        String timestamp = headers.getFirst("timestamp");
        String sign = headers.getFirst("sign");
        // sk从数据库里查询
        User invokeUser = interfaceInfoDubbo.getInvokeUser(accessKey);
        if (invokeUser == null) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);// 设置状态码
            return exchange.getResponse().setComplete();// 拦截
        }
        String secretKey = invokeUser.getSecretKey();
        // TODO 这里的所有参数都应该校验
        ServerHttpResponse response = exchange.getResponse();
        // 时间不超过5分钟
        long currentTime = System.currentTimeMillis() / 1000;
        if (timestamp == null || (currentTime - Integer.parseInt(timestamp)) > 60 * 5) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);// 设置状态码
            return exchange.getResponse().setComplete();// 拦截
        }
        HashMap<String, String> serverHeaders = new HashMap<>();
        serverHeaders.put("accessKey", accessKey);
        // TODO 这里的随机数可以保存在Redis、数据库等地方
        serverHeaders.put("nonce", nonce);
        serverHeaders.put("timestamp", timestamp);
        String serverSign = SignUtils.getSign(serverHeaders, secretKey);
        if (!serverSign.equals(sign)) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);// 设置状态码
            return exchange.getResponse().setComplete();// 拦截
        }
        // 查询接口是否存在
        InterfaceInfo interfaceInfo = interfaceInfoDubbo.getInterfaceInfo(path, method.toString());
        if (interfaceInfo == null) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);// 设置状态码
            return exchange.getResponse().setComplete();// 拦截
        }
        ServerHttpResponse originalResponse = exchange.getResponse();
        DataBufferFactory bufferFactory = originalResponse.bufferFactory();
        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                if (body instanceof Flux) {
                    Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                    return super.writeWith(fluxBody.map(dataBuffer -> {
                        byte[] content = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(content);
                        DataBufferUtils.release(dataBuffer);//释放掉内存
                        // 构建日志
                        StringBuilder sb = new StringBuilder(200);
                        List<Object> rspArgs = new ArrayList<>();
                        rspArgs.add(originalResponse.getStatusCode());
                        /*---------------真正的响应数据---------------*/
                        String data = new String(content, StandardCharsets.UTF_8);
                        sb.append(data);
                        // 7. 响应日志
                        log.info("Response Data: {}", data);
                        log.info("-------------------------------");
                        // 8. 调用成功，调用次数 + 1
                        interfaceInfoDubbo.invokeTimeChange(interfaceInfo.getId(), invokeUser.getId());
                        return bufferFactory.wrap(content);
                    }));
                } else {
                    log.error("{} 响应code异常", getStatusCode());
                }
                return super.writeWith(body);
            }
        };
        // 请求转发，调用接口
        return chain.filter(exchange.mutate().response(decoratedResponse).build())
                .onErrorResume(e -> {
                    // 9. 调用失败，返回一个规范的错误码
                    log.error("The interface is offline...", e);
                    response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
                    String errorMessage = "接口暂时不可用，请稍后重试！\n（绝对不是因为提供者跑路了！！！）";
                    DataBuffer buffer = response.bufferFactory().wrap(errorMessage.getBytes(StandardCharsets.UTF_8));
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return response.writeWith(Mono.just(buffer));
                });
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
