/*
 * Copyright 1999-2020 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.naming.push.v2;

import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.api.naming.utils.NamingUtils;
import com.alibaba.nacos.api.utils.StringUtils;
import com.alibaba.nacos.common.notify.Event;
import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.common.notify.listener.SmartSubscriber;
import com.alibaba.nacos.naming.core.v2.client.manager.ClientManager;
import com.alibaba.nacos.naming.core.v2.client.manager.ClientManagerDelegate;
import com.alibaba.nacos.naming.core.v2.event.publisher.NamingEventPublisherFactory;
import com.alibaba.nacos.naming.core.v2.event.service.ServiceEvent;
import com.alibaba.nacos.naming.core.v2.index.NamingFuzzyWatchContextService;
import com.alibaba.nacos.naming.core.v2.index.ClientServiceIndexesManager;
import com.alibaba.nacos.naming.core.v2.index.ServiceStorage;
import com.alibaba.nacos.naming.core.v2.metadata.NamingMetadataManager;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import com.alibaba.nacos.naming.misc.SwitchDomain;
import com.alibaba.nacos.naming.monitor.MetricsMonitor;
import com.alibaba.nacos.naming.pojo.Subscriber;
import com.alibaba.nacos.naming.push.NamingSubscriberService;
import com.alibaba.nacos.naming.push.v2.executor.PushExecutorDelegate;
import com.alibaba.nacos.naming.push.v2.task.FuzzyWatchChangeNotifyTask;
import com.alibaba.nacos.naming.push.v2.task.FuzzyWatchInitNotifyTask;
import com.alibaba.nacos.naming.push.v2.task.FuzzyWatchPushDelayTaskEngine;
import com.alibaba.nacos.naming.push.v2.task.PushDelayTask;
import com.alibaba.nacos.naming.push.v2.task.PushDelayTaskExecuteEngine;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Naming subscriber service for v2.x.
 *
 * @author xiweng.yy
 */
@org.springframework.stereotype.Service
public class NamingSubscriberServiceV2Impl extends SmartSubscriber implements NamingSubscriberService {
    
    private static final int PARALLEL_SIZE = 100;
    
    private final ClientManager clientManager;
    
    private final ClientServiceIndexesManager indexesManager;
    
    private final PushDelayTaskExecuteEngine delayTaskEngine;
    
    private final FuzzyWatchPushDelayTaskEngine fuzzyWatchPushDelayTaskEngine;
    
    public NamingSubscriberServiceV2Impl(ClientManagerDelegate clientManager,
            ClientServiceIndexesManager indexesManager, NamingFuzzyWatchContextService namingFuzzyWatchContextService, ServiceStorage serviceStorage,
            NamingMetadataManager metadataManager, PushExecutorDelegate pushExecutor, SwitchDomain switchDomain) {
        this.clientManager = clientManager;
        this.indexesManager = indexesManager;
        this.delayTaskEngine = new PushDelayTaskExecuteEngine(clientManager, indexesManager, serviceStorage,
                metadataManager, pushExecutor, switchDomain);
        this.fuzzyWatchPushDelayTaskEngine = new FuzzyWatchPushDelayTaskEngine(
                namingFuzzyWatchContextService,
                serviceStorage, metadataManager, pushExecutor, switchDomain);
        NotifyCenter.registerSubscriber(this, NamingEventPublisherFactory.getInstance());
        
    }
    
    @Override
    public Collection<Subscriber> getSubscribers(String namespaceId, String serviceName) {
        String serviceNameWithoutGroup = NamingUtils.getServiceName(serviceName);
        String groupName = NamingUtils.getGroupName(serviceName);
        Service service = Service.newService(namespaceId, groupName, serviceNameWithoutGroup);
        return getSubscribers(service);
    }
    
    @Override
    public Collection<Subscriber> getSubscribers(Service service) {
        Collection<Subscriber> result = new HashSet<>();
        for (String each : indexesManager.getAllClientsSubscribeService(service)) {
            result.add(clientManager.getClient(each).getSubscriber(service));
        }
        return result;
    }
    
    @Override
    public Collection<Subscriber> getFuzzySubscribers(String namespaceId, String serviceName) {
        Collection<Subscriber> result = new HashSet<>();
        Stream<Service> serviceStream = getServiceStream();
        String serviceNamePattern = NamingUtils.getServiceName(serviceName);
        String groupNamePattern = NamingUtils.getGroupName(serviceName);
        serviceStream.filter(service -> service.getNamespace().equals(namespaceId) && service.getName()
                .contains(serviceNamePattern) && service.getGroup().contains(groupNamePattern))
                .forEach(service -> result.addAll(getSubscribers(service)));
        return result;
    }
    
    @Override
    public Collection<Subscriber> getFuzzySubscribers(Service service) {
        return getFuzzySubscribers(service.getNamespace(), service.getGroupedServiceName());
    }
    
    @Override
    public List<Class<? extends Event>> subscribeTypes() {
        List<Class<? extends Event>> result = new LinkedList<>();
        result.add(ServiceEvent.ServiceChangedEvent.class);
        result.add(ServiceEvent.ServiceSubscribedEvent.class);
        result.add(ServiceEvent.ServiceFuzzyWatchInitEvent.class);
        return result;
    }
    
    @Override
    public void onEvent(Event event) {
        if (event instanceof ServiceEvent.ServiceChangedEvent) {
            // If service changed, push to all subscribers.
            ServiceEvent.ServiceChangedEvent serviceChangedEvent = (ServiceEvent.ServiceChangedEvent) event;
            Service service = serviceChangedEvent.getService();
            delayTaskEngine.addTask(service, new PushDelayTask(service, PushConfig.getInstance().getPushTaskDelay()));
            genetateFuzzyWatchChangeNotifyTask(service,serviceChangedEvent.getChangedType());
            MetricsMonitor.incrementServiceChangeCount(service);
        } else if (event instanceof ServiceEvent.ServiceSubscribedEvent) {
            // If service is subscribed by one client, only push this client.
            ServiceEvent.ServiceSubscribedEvent subscribedEvent = (ServiceEvent.ServiceSubscribedEvent) event;
            Service service = subscribedEvent.getService();
            delayTaskEngine.addTask(service, new PushDelayTask(service, PushConfig.getInstance().getPushTaskDelay(),
                    subscribedEvent.getClientId()));
        } else if (event instanceof ServiceEvent.ServiceFuzzyWatchInitEvent) {
            ServiceEvent.ServiceFuzzyWatchInitEvent serviceFuzzyWatchInitEvent = (ServiceEvent.ServiceFuzzyWatchInitEvent) event;
            
            String completedPattern = serviceFuzzyWatchInitEvent.getPattern();
            String clientId = serviceFuzzyWatchInitEvent.getClientId();
            String taskKey = getTaskKey(clientId, completedPattern);
            Collection<String> matchedService = serviceFuzzyWatchInitEvent.getMatchedService().stream()
                    .map(Service::getGroupedServiceName)
                    .collect(Collectors.toSet());
            int originSize = matchedService.size();
            // watch init push task is specify by client id with pattern
            // The key is just used to differentiate between different initialization tasks and merge them if needed.
            FuzzyWatchInitNotifyTask fuzzyWatchInitNotifyTask = new FuzzyWatchInitNotifyTask(clientId, completedPattern,
                    matchedService, originSize, PushConfig.getInstance().getPushTaskDelay(), false);
            fuzzyWatchPushDelayTaskEngine.addTask(FuzzyWatchPushDelayTaskEngine.getTaskKey(fuzzyWatchInitNotifyTask), fuzzyWatchInitNotifyTask);
        }
    }
    
    private void genetateFuzzyWatchChangeNotifyTask(Service service,String changedType){
        // watch notify push task specify by service
        for (String clientId : fuzzyWatchPushDelayTaskEngine.getNamingFuzzyWatchContextService()
                .getFuzzyWatchedClients(service)) {
            String serviceKey=NamingUtils.getServiceKey(service.getNamespace(),service.getGroup(),service.getName());
            FuzzyWatchChangeNotifyTask fuzzyWatchChangeNotifyTask = new FuzzyWatchChangeNotifyTask(serviceKey, changedType,
                    clientId, PushConfig.getInstance().getPushTaskDelay());
            fuzzyWatchPushDelayTaskEngine.addTask(FuzzyWatchPushDelayTaskEngine.getTaskKey(fuzzyWatchChangeNotifyTask),fuzzyWatchChangeNotifyTask );
        }
    }
    
    String getTaskKey(String clientId, String pattern) {
        if (StringUtils.isBlank(clientId)) {
            throw new IllegalArgumentException("Param 'clientId' is illegal, clientId is blank");
        }
        if (StringUtils.isBlank(pattern)) {
            throw new IllegalArgumentException("Param 'pattern' is illegal, pattern is blank");
        }
        return clientId + Constants.SERVICE_INFO_SPLITER + pattern;
    }
    
    private Stream<Service> getServiceStream() {
        Collection<Service> services = indexesManager.getSubscribedService();
        return services.size() > PARALLEL_SIZE ? services.parallelStream() : services.stream();
    }
    
    public int getPushPendingTaskCount() {
        return delayTaskEngine.size();
    }
}
