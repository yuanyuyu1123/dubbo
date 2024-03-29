/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dubbo.rpc.protocol.tri.call;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.config.ConfigurationUtils;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.rpc.HeaderFilter;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcInvocation;
import org.apache.dubbo.rpc.TriRpcStatus;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.model.MethodDescriptor;
import org.apache.dubbo.rpc.model.MethodDescriptor.RpcType;
import org.apache.dubbo.rpc.model.PackableMethod;
import org.apache.dubbo.rpc.model.PackableMethodFactory;
import org.apache.dubbo.rpc.model.ProviderModel;
import org.apache.dubbo.rpc.model.ServiceDescriptor;
import org.apache.dubbo.rpc.protocol.tri.ClassLoadUtil;
import org.apache.dubbo.rpc.protocol.tri.TripleCustomerProtocolWapper;
import org.apache.dubbo.rpc.protocol.tri.stream.ServerStream;
import org.apache.dubbo.rpc.service.ServiceDescriptorInternalCache;

import io.netty.handler.codec.http.HttpHeaderNames;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static org.apache.dubbo.common.constants.CommonConstants.DEFAULT_KEY;
import static org.apache.dubbo.common.constants.CommonConstants.DUBBO_PACKABLE_METHOD_FACTORY;

public class ReflectionAbstractServerCall extends AbstractServerCall {

    private static final String PACKABLE_METHOD_CACHE = "PACKABLE_METHOD_CACHE";
    private final List<HeaderFilter> headerFilters;
    private List<MethodDescriptor> methodDescriptors;

    public ReflectionAbstractServerCall(Invoker<?> invoker,
                                        ServerStream serverStream,
                                        FrameworkModel frameworkModel,
                                        String acceptEncoding,
                                        String serviceName,
                                        String methodName,
                                        List<HeaderFilter> headerFilters,
                                        Executor executor) {
        super(invoker, serverStream, frameworkModel,
            getServiceDescriptor(invoker.getUrl()),
            acceptEncoding, serviceName, methodName,
            executor);
        this.headerFilters = headerFilters;
    }

    private static ServiceDescriptor getServiceDescriptor(URL url) {
        ProviderModel providerModel = (ProviderModel) url.getServiceModel();
        if (providerModel == null || providerModel.getServiceModel() == null) {
            return null;
        }
        return providerModel.getServiceModel();
    }

    private boolean isEcho(String methodName) {
        return CommonConstants.$ECHO.equals(methodName);
    }

    private boolean isGeneric(String methodName) {
        return CommonConstants.$INVOKE.equals(methodName) || CommonConstants.$INVOKE_ASYNC.equals(
            methodName);
    }

    @Override
    public void startCall() {
        if (isGeneric(methodName)) {
            // There should be one and only one
            methodDescriptor = ServiceDescriptorInternalCache.genericService()
                .getMethods(methodName).get(0);
        } else if (isEcho(methodName)) {
            // There should be one and only one
            methodDescriptor = ServiceDescriptorInternalCache.echoService().getMethods(methodName)
                .get(0);
        } else {
            methodDescriptors = serviceDescriptor.getMethods(methodName);
            // try lower-case method
            if (CollectionUtils.isEmpty(methodDescriptors)) {
                final String lowerMethod =
                    Character.toLowerCase(methodName.charAt(0)) + methodName.substring(1);
                methodDescriptors = serviceDescriptor.getMethods(lowerMethod);
            }
            if (CollectionUtils.isEmpty(methodDescriptors)) {
                responseErr(TriRpcStatus.UNIMPLEMENTED.withDescription(
                    "Method : " + methodName + " not found of service:" + serviceName));
                return;
            }
            // In most cases there is only one method
            if (methodDescriptors.size() == 1) {
                methodDescriptor = methodDescriptors.get(0);
            }
            // generated unary method ,use unary type
            // Response foo(Request)
            // void foo(Request,StreamObserver<Response>)
            if (methodDescriptors.size() == 2) {
                if (methodDescriptors.get(1).getRpcType() == RpcType.SERVER_STREAM) {
                    methodDescriptor = methodDescriptors.get(0);
                } else if (methodDescriptors.get(0).getRpcType() == RpcType.SERVER_STREAM) {
                    methodDescriptor = methodDescriptors.get(1);
                }
            }
        }
        if (methodDescriptor != null) {
            loadPackableMethod(invoker.getUrl());
        }
        trySetListener();
        if (listener == null) {
            // wrap request , need one message
            request(1);
        }
    }

    private void trySetListener() {
        if (listener != null) {
            return;
        }
        if (methodDescriptor == null) {
            return;
        }
        if (isClosed()) {
            return;
        }
        RpcInvocation invocation = buildInvocation(methodDescriptor);
        if (isClosed()) {
            return;
        }
        headerFilters.forEach(f -> f.invoke(invoker, invocation));
        if (isClosed()) {
            return;
        }
        listener = ReflectionAbstractServerCall.this.startInternalCall(invocation,
            methodDescriptor, invoker);
    }

    @Override
    protected Object parseSingleMessage(byte[] data) throws Exception {
        trySetMethodDescriptor(data);
        trySetListener();
        if (isClosed()) {
            return null;
        }
        ClassLoadUtil.switchContextLoader(
            invoker.getUrl().getServiceModel().getClassLoader());
        return packableMethod.getRequestUnpack().unpack(data);
    }


    private void trySetMethodDescriptor(byte[] data) {
        if (methodDescriptor != null) {
            return;
        }
        final TripleCustomerProtocolWapper.TripleRequestWrapper request;
        request = TripleCustomerProtocolWapper.TripleRequestWrapper.parseFrom(data);

        final String[] paramTypes = request.getArgTypes()
            .toArray(new String[request.getArgs().size()]);
        // wrapper mode the method can overload so maybe list
        for (MethodDescriptor descriptor : methodDescriptors) {
            // params type is array
            if (Arrays.equals(descriptor.getCompatibleParamSignatures(), paramTypes)) {
                methodDescriptor = descriptor;
                break;
            }
        }
        if (methodDescriptor == null) {
            ReflectionAbstractServerCall.this.close(TriRpcStatus.UNIMPLEMENTED.withDescription(
                "Method :" + methodName + "[" + Arrays.toString(
                    paramTypes) + "] " + "not found of service:"
                    + serviceDescriptor.getInterfaceName()), null);
            return;
        }
        loadPackableMethod(invoker.getUrl());
    }

    @SuppressWarnings("unchecked")
    private void loadPackableMethod(URL url) {
        Map<MethodDescriptor, PackableMethod> cacheMap = (Map<MethodDescriptor, PackableMethod>) url.getServiceModel()
            .getServiceMetadata()
            .getAttributeMap()
            .computeIfAbsent(PACKABLE_METHOD_CACHE, (k) -> new ConcurrentHashMap<>());
        packableMethod = cacheMap.computeIfAbsent(methodDescriptor,
            (md) -> frameworkModel.getExtensionLoader(PackableMethodFactory.class)
                .getExtension(ConfigurationUtils.getGlobalConfiguration(url.getApplicationModel()).getString(DUBBO_PACKABLE_METHOD_FACTORY, DEFAULT_KEY))
                .create(methodDescriptor, url, (String) requestMetadata.get(HttpHeaderNames.CONTENT_TYPE.toString())));
    }

}
