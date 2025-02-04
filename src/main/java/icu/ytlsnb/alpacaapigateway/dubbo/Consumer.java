package icu.ytlsnb.alpacaapigateway.dubbo;

import icu.ytlsnb.dubbo.InterfaceInfoDubbo;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class Consumer implements CommandLineRunner {
    // scope="remote" is used to force mock remote service call
    @DubboReference(scope = "remote")
    private InterfaceInfoDubbo interfaceInfoDubbo;

    @Override
    public void run(String... args) throws Exception {
        String result = interfaceInfoDubbo.getName("zhangsan");
        System.out.println("Receive result ======> " + result);
    }
}
