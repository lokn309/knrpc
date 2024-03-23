package cn.lokn.knrpc.core.consumer;

import cn.lokn.knrpc.core.api.RpcContext;
import cn.lokn.knrpc.core.api.RpcRequest;
import cn.lokn.knrpc.core.api.RpcResponse;
import cn.lokn.knrpc.core.consumer.http.OkHttpInvoker;
import cn.lokn.knrpc.core.meta.InstanceMeta;
import cn.lokn.knrpc.core.util.MethodUtils;
import cn.lokn.knrpc.core.util.TypeUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;


/**
 * @description: 消费端动态代理处理类
 * @author: lokn
 * @date: 2024/03/10 23:03
 */
public class KNInvocationHandler implements InvocationHandler {

    Class<?> service;
    RpcContext context;
    List<InstanceMeta> providers;

    HttpInvoker httpInvoker = new OkHttpInvoker();

    public KNInvocationHandler(Class<?> service, RpcContext context, List<InstanceMeta> providers) {
        this.service = service;
        this.context = context;
        this.providers = providers;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (MethodUtils.checkLocalMethod(method.getName())) {
            return null;
        }

        RpcRequest request = new RpcRequest();
        request.setService(service.getCanonicalName());
        request.setMethodSign(MethodUtils.methodSign(method));
        request.setArgs(args);

        final List<InstanceMeta> nodes = context.getRouter().route(providers);
        InstanceMeta instance = context.getLoadBalancer().choose(nodes);
        System.out.println("loadBalancer.choose(urls) == " + instance);
        RpcResponse<?> rpcResponse = httpInvoker.post(request, instance.getUrl());

        if (rpcResponse.isStatus()) {
            final Object data = rpcResponse.getData();
            return TypeUtils.castMethodResult(method, data);
        } else {
            final Exception ex = rpcResponse.getEx();
            throw new RuntimeException(ex);
        }
    }

}
