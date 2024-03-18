package cn.lokn.knrpc.core.provider;

import ch.qos.logback.core.net.server.Client;
import cn.lokn.knrpc.core.annotation.KNProvider;
import cn.lokn.knrpc.core.api.RegistryCenter;
import cn.lokn.knrpc.core.api.RpcRequest;
import cn.lokn.knrpc.core.api.RpcResponse;
import cn.lokn.knrpc.core.meta.ProviderMeta;
import cn.lokn.knrpc.core.util.MethodUtils;
import cn.lokn.knrpc.core.util.TypeUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @description: 服务提供者的启动类
 * @author: lokn
 * @date: 2024/03/07 23:27
 */
@Data
public class ProviderBoostrap implements ApplicationContextAware {

    /**
     * 当 {@link ProviderBoostrap } 被丢到 spring 容器后，applicationContext 会被自动加载到容器中
     * 注意：属性名称需要用 applicationContext
     */
    ApplicationContext applicationContext; // 获取所有的bean

    // 获取所有的provider
    // 同一将方法签名解析后放到 skeleton 桩子中，避免每次都解析请求参数
    private MultiValueMap<String, ProviderMeta> skeleton = new LinkedMultiValueMap<>();

    private String instance;

    @Value("${server.port}")
    private String port;

    /**
     * 构建获取所有的provider
     */

    @PostConstruct
    public void init() {
        // 获取所有加了自定义 @KNProvider 注解的bean
        final Map<String, Object> providers = applicationContext.getBeansWithAnnotation(KNProvider.class);
        providers.forEach((x, y) -> System.out.println(x));
        providers.values().forEach(
                this::genInterface
        );
    }

    /**
     * 延迟服务暴露，只有当spring bean 完成后才向zk注册
     */
    @SneakyThrows
    public void start() {
        String ip = InetAddress.getLocalHost().getHostAddress();
        instance = ip + "_" + port;
        skeleton.keySet().forEach(this::registerService);
    }


    @PreDestroy
    public void stop() {
        skeleton.keySet().forEach(this::unRegisterService);
    }

    private void unRegisterService(String service) {
        RegistryCenter rc = applicationContext.getBean(RegistryCenter.class);
        rc.unregister(service, instance);
    }


    private void registerService(String service) {
        RegistryCenter rc = applicationContext.getBean(RegistryCenter.class);
        rc.register(service, instance);
    }

    private void genInterface(Object x) {
        Arrays.stream(x.getClass().getInterfaces()).forEach(itfer -> {
            final Method[] methods = itfer.getMethods();
            for (Method method : methods) {
                if (MethodUtils.checkLocalMethod(method)) {
                    continue;
                }
                createProvider(itfer, x, method);
            }
        });
    }

    private void createProvider(Class<?> itfer, Object x, Method method) {
        ProviderMeta meta = new ProviderMeta();
        meta.setMethod(method);
        meta.setServiceImpl(x);
        meta.setMethodSign(MethodUtils.methodSign(method));
        System.out.println(" create a provider : " + meta);
        skeleton.add(itfer.getCanonicalName(), meta);
    }

    public RpcResponse invoke(RpcRequest request) {
        if (MethodUtils.checkLocalMethod(request.getMethodSign())) {
            return null;
        }
        List<ProviderMeta> providerMetas = skeleton.get(request.getService());
        RpcResponse rpcResponse = new RpcResponse();
        try {
            ProviderMeta meta = findProviderMeta(providerMetas, request.getMethodSign());
            Method method = meta.getMethod();
            Object[] args = processArgs(request.getArgs(), method.getParameterTypes());
            final Object result = method.invoke(meta.getServiceImpl(), args);
            rpcResponse.setStatus(true);
            rpcResponse.setData(result);
            return rpcResponse;
        } catch (InvocationTargetException e) {
            e.printStackTrace();
            rpcResponse.setEx(new RuntimeException(e.getTargetException().getMessage()));
        } catch (IllegalAccessException e) {
            e.printStackTrace();
            rpcResponse.setEx(new RuntimeException(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            rpcResponse.setEx(new RuntimeException(e.getMessage()));
        }
        return rpcResponse;
    }

    private Object[] processArgs(Object[] args, Class<?>[] parameterTypes) {
        if (args == null || args.length == 0) return args;
        Object[] result = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            result[i] = TypeUtils.cast(args[i], parameterTypes[i]);
        }
        return result;
    }

    private ProviderMeta findProviderMeta(List<ProviderMeta> providerMetas, String methodSign) {
        final Optional<ProviderMeta> result = providerMetas.stream().filter(meta -> meta.getMethodSign().equals(methodSign)).findFirst();
        return result.orElse(null);
    }

}
