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

package org.apache.shardingsphere.driver.orchestration.internal.datasource;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.Subscribe;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.shardingsphere.cluster.configuration.config.ClusterConfiguration;
import org.apache.shardingsphere.cluster.facade.ClusterFacade;
import org.apache.shardingsphere.cluster.facade.init.ClusterInitFacade;
import org.apache.shardingsphere.cluster.heartbeat.event.HeartbeatDetectNoticeEvent;
import org.apache.shardingsphere.driver.jdbc.core.datasource.ShardingSphereDataSource;
import org.apache.shardingsphere.driver.orchestration.internal.circuit.datasource.CircuitBreakerDataSource;
import org.apache.shardingsphere.driver.orchestration.internal.util.DataSourceConverter;
import org.apache.shardingsphere.infra.config.DataSourceConfiguration;
import org.apache.shardingsphere.infra.config.RuleConfiguration;
import org.apache.shardingsphere.infra.database.DefaultSchema;
import org.apache.shardingsphere.infra.metadata.ShardingSphereMetaData;
import org.apache.shardingsphere.infra.metadata.schema.RuleSchemaMetaData;
import org.apache.shardingsphere.infra.rule.ShardingSphereRule;
import org.apache.shardingsphere.infra.rule.StatusContainedRule;
import org.apache.shardingsphere.infra.rule.event.impl.DataSourceNameDisabledEvent;
import org.apache.shardingsphere.kernel.context.SchemaContext;
import org.apache.shardingsphere.kernel.context.SchemaContexts;
import org.apache.shardingsphere.kernel.context.schema.ShardingSphereSchema;
import org.apache.shardingsphere.masterslave.rule.MasterSlaveRule;
import org.apache.shardingsphere.orchestration.center.config.OrchestrationConfiguration;
import org.apache.shardingsphere.orchestration.core.common.event.DataSourceChangedEvent;
import org.apache.shardingsphere.orchestration.core.common.event.PropertiesChangedEvent;
import org.apache.shardingsphere.orchestration.core.common.event.RuleConfigurationsChangedEvent;
import org.apache.shardingsphere.orchestration.core.configcenter.ConfigCenter;
import org.apache.shardingsphere.orchestration.core.facade.ShardingOrchestrationFacade;
import org.apache.shardingsphere.orchestration.core.metadatacenter.event.MetaDataChangedEvent;
import org.apache.shardingsphere.orchestration.core.registrycenter.event.CircuitStateChangedEvent;
import org.apache.shardingsphere.orchestration.core.registrycenter.event.DisabledStateChangedEvent;
import org.apache.shardingsphere.orchestration.core.registrycenter.schema.OrchestrationSchema;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orchestration ShardingSphere data source.
 */
@Getter(AccessLevel.PROTECTED)
public class OrchestrationShardingSphereDataSource extends AbstractOrchestrationDataSource {

    private static final String DELIMITER = "@";

    private static final String THREAD_ID = ManagementFactory.getRuntimeMXBean().getName().split(DELIMITER)[0];
    
    private ShardingSphereDataSource dataSource;
    
    public OrchestrationShardingSphereDataSource(final OrchestrationConfiguration orchestrationConfig) throws SQLException {
        super(new ShardingOrchestrationFacade(orchestrationConfig, Collections.singletonList(DefaultSchema.LOGIC_NAME), THREAD_ID));
        ConfigCenter configService = getShardingOrchestrationFacade().getConfigCenter();
        Collection<RuleConfiguration> configurations = configService.loadRuleConfigurations(DefaultSchema.LOGIC_NAME);
        Preconditions.checkState(!configurations.isEmpty(), "Missing the sharding rule configuration on registry center");
        Map<String, DataSourceConfiguration> dataSourceConfigurations = configService.loadDataSourceConfigurations(DefaultSchema.LOGIC_NAME);
        dataSource = new ShardingSphereDataSource(DataSourceConverter.getDataSourceMap(dataSourceConfigurations), configurations, configService.loadProperties());
        initShardingOrchestrationFacade();
        disableDataSources();
        persistMetaData(dataSource.getSchemaContexts().getDefaultSchemaContext().getSchema().getMetaData().getSchema());
        initCluster();
    }
    
    public OrchestrationShardingSphereDataSource(final ShardingSphereDataSource shardingSphereDataSource, final OrchestrationConfiguration orchestrationConfig) {
        super(new ShardingOrchestrationFacade(orchestrationConfig, Collections.singletonList(DefaultSchema.LOGIC_NAME), THREAD_ID));
        dataSource = shardingSphereDataSource;
        initShardingOrchestrationFacade(Collections.singletonMap(DefaultSchema.LOGIC_NAME, DataSourceConverter.getDataSourceConfigurationMap(dataSource.getDataSourceMap())),
                getRuleConfigurationMap(), dataSource.getSchemaContexts().getProps().getProps());
        disableDataSources();
        persistMetaData(dataSource.getSchemaContexts().getDefaultSchemaContext().getSchema().getMetaData().getSchema());
        initCluster();
    }
    
    public OrchestrationShardingSphereDataSource(final OrchestrationConfiguration orchestrationConfig, final ClusterConfiguration clusterConfiguration) throws SQLException {
        super(new ShardingOrchestrationFacade(orchestrationConfig, Collections.singletonList(DefaultSchema.LOGIC_NAME), THREAD_ID));
        ConfigCenter configService = getShardingOrchestrationFacade().getConfigCenter();
        Collection<RuleConfiguration> configurations = configService.loadRuleConfigurations(DefaultSchema.LOGIC_NAME);
        Preconditions.checkState(!configurations.isEmpty(), "Missing the sharding rule configuration on registry center");
        Map<String, DataSourceConfiguration> dataSourceConfigurations = configService.loadDataSourceConfigurations(DefaultSchema.LOGIC_NAME);
        dataSource = new ShardingSphereDataSource(DataSourceConverter.getDataSourceMap(dataSourceConfigurations), configurations, configService.loadProperties());
        initShardingOrchestrationFacade(clusterConfiguration);
        disableDataSources();
        persistMetaData(dataSource.getSchemaContexts().getDefaultSchemaContext().getSchema().getMetaData().getSchema());
        initCluster();
    }
    
    public OrchestrationShardingSphereDataSource(final ShardingSphereDataSource shardingSphereDataSource,
                                                 final OrchestrationConfiguration orchestrationConfig, final ClusterConfiguration clusterConfiguration) {
        super(new ShardingOrchestrationFacade(orchestrationConfig, Collections.singletonList(DefaultSchema.LOGIC_NAME), THREAD_ID));
        dataSource = shardingSphereDataSource;
        initShardingOrchestrationFacade(Collections.singletonMap(DefaultSchema.LOGIC_NAME, DataSourceConverter.getDataSourceConfigurationMap(dataSource.getDataSourceMap())),
                getRuleConfigurationMap(), dataSource.getSchemaContexts().getProps().getProps(), clusterConfiguration);
        disableDataSources();
        persistMetaData(dataSource.getSchemaContexts().getDefaultSchemaContext().getSchema().getMetaData().getSchema());
        initCluster();
    }
    
    private Map<String, Collection<RuleConfiguration>> getRuleConfigurationMap() {
        Map<String, Collection<RuleConfiguration>> result = new LinkedHashMap<>(1, 1);
        result.put(DefaultSchema.LOGIC_NAME, dataSource.getSchemaContexts().getDefaultSchemaContext().getSchema().getConfigurations());
        return result;
    }
    
    @Override
    public final Connection getConnection() {
        return dataSource.getSchemaContexts().isCircuitBreak() ? new CircuitBreakerDataSource().getConnection() : getDataSource().getConnection();
    }
    
    /**
     * Renew meta data of the schema.
     *
     * @param event meta data changed event.
     */
    @Subscribe
    public final synchronized void renew(final MetaDataChangedEvent event) {
        if (!event.getSchemaNames().contains(DefaultSchema.LOGIC_NAME)) {
            return;
        }
        Map<String, SchemaContext> schemaContexts = new HashMap<>(dataSource.getSchemaContexts().getSchemaContexts().size());
        SchemaContext oldSchemaContext = dataSource.getSchemaContexts().getSchemaContexts().get(DefaultSchema.LOGIC_NAME);
        schemaContexts.put(DefaultSchema.LOGIC_NAME, new SchemaContext(oldSchemaContext.getName(),
                getChangedShardingSphereSchema(oldSchemaContext.getSchema(), event.getRuleSchemaMetaData()), oldSchemaContext.getRuntimeContext()));
        dataSource = new ShardingSphereDataSource(new SchemaContexts(schemaContexts, dataSource.getSchemaContexts().getProps(), dataSource.getSchemaContexts().getAuthentication()));
    }
    
    /**
     * Renew sharding rule.
     *
     * @param ruleConfigurationsChangedEvent rule configurations changed event
     */
    @Subscribe
    @SneakyThrows
    public final synchronized void renew(final RuleConfigurationsChangedEvent ruleConfigurationsChangedEvent) {
        if (!ruleConfigurationsChangedEvent.getShardingSchemaName().contains(DefaultSchema.LOGIC_NAME)) {
            return;
        }
        dataSource = new ShardingSphereDataSource(dataSource.getDataSourceMap(), 
                ruleConfigurationsChangedEvent.getRuleConfigurations(), dataSource.getSchemaContexts().getProps().getProps());
    }
    
    /**
     * Renew sharding data source.
     *
     * @param dataSourceChangedEvent data source changed event
     */
    @Subscribe
    @SneakyThrows
    public final synchronized void renew(final DataSourceChangedEvent dataSourceChangedEvent) {
        if (!dataSourceChangedEvent.getShardingSchemaName().contains(DefaultSchema.LOGIC_NAME)) {
            return;
        }
        Map<String, DataSourceConfiguration> dataSourceConfigurations = dataSourceChangedEvent.getDataSourceConfigurations();
        dataSource.close(getDeletedDataSources(dataSourceConfigurations));
        dataSource.close(getModifiedDataSources(dataSourceConfigurations).keySet());
        dataSource = new ShardingSphereDataSource(getChangedDataSources(dataSource.getDataSourceMap(), dataSourceConfigurations), 
                dataSource.getSchemaContexts().getDefaultSchemaContext().getSchema().getConfigurations(), dataSource.getSchemaContexts().getProps().getProps());
        getDataSourceConfigurations().clear();
        getDataSourceConfigurations().putAll(dataSourceConfigurations);
    }
    
    /**
     * Renew properties.
     *
     * @param propertiesChangedEvent properties changed event
     */
    @SneakyThrows
    @Subscribe
    public final synchronized void renew(final PropertiesChangedEvent propertiesChangedEvent) {
        dataSource = new ShardingSphereDataSource(dataSource.getDataSourceMap(), 
                dataSource.getSchemaContexts().getDefaultSchemaContext().getSchema().getConfigurations(), propertiesChangedEvent.getProps());
    }
    
    /**
     * Renew circuit breaker state.
     *
     * @param event circuit state changed event
     */
    @Subscribe
    public synchronized void renew(final CircuitStateChangedEvent event) {
        SchemaContexts oldSchemaContexts = dataSource.getSchemaContexts();
        SchemaContexts schemaContexts = new SchemaContexts(oldSchemaContexts.getSchemaContexts(), oldSchemaContexts.getProps(), oldSchemaContexts.getAuthentication(), event.isCircuitBreak());
        dataSource = new ShardingSphereDataSource(schemaContexts);
    }
    
    /**
     * Renew disabled data source names.
     *
     * @param disabledStateChangedEvent disabled state changed event
     */
    @Subscribe
    public synchronized void renew(final DisabledStateChangedEvent disabledStateChangedEvent) {
        OrchestrationSchema orchestrationSchema = disabledStateChangedEvent.getOrchestrationSchema();
        if (DefaultSchema.LOGIC_NAME.equals(orchestrationSchema.getSchemaName())) {
            for (ShardingSphereRule each : dataSource.getSchemaContexts().getDefaultSchemaContext().getSchema().getRules()) {
                if (each instanceof StatusContainedRule) {
                    ((StatusContainedRule) each).updateRuleStatus(new DataSourceNameDisabledEvent(orchestrationSchema.getDataSourceName(), disabledStateChangedEvent.isDisabled()));
                }
            }
        }
    }
    
    /**
     * Heart beat detect.
     *
     * @param event heart beat detect notice event
     */
    @Subscribe
    public synchronized void heartbeat(final HeartbeatDetectNoticeEvent event) {
        if (ClusterInitFacade.isEnabled()) {
            ClusterFacade.getInstance().detectHeartbeat(dataSource.getSchemaContexts().getSchemaContexts());
        }
    }
    
    private ShardingSphereSchema getChangedShardingSphereSchema(final ShardingSphereSchema oldShardingSphereSchema, final RuleSchemaMetaData newRuleSchemaMetaData) {
        ShardingSphereMetaData metaData = new ShardingSphereMetaData(oldShardingSphereSchema.getMetaData().getDataSources(), newRuleSchemaMetaData);
        return new ShardingSphereSchema(oldShardingSphereSchema.getDatabaseType(), oldShardingSphereSchema.getConfigurations(),
                oldShardingSphereSchema.getRules(), oldShardingSphereSchema.getDataSources(), metaData);
    }
    
    private void disableDataSources() {
        Collection<String> disabledDataSources = ShardingOrchestrationFacade.getInstance().getRegistryCenter().loadDisabledDataSources();
        if (!disabledDataSources.isEmpty()) {
            dataSource.getSchemaContexts().getSchemaContexts().entrySet().forEach(entry -> entry.getValue().getSchema().getRules()
                    .stream().filter(each -> each instanceof MasterSlaveRule).forEach(e -> disableDataSources((MasterSlaveRule) e, disabledDataSources, entry.getKey())));
        }
    }
    
    private void disableDataSources(final MasterSlaveRule masterSlaveRule,
                                    final Collection<String> disabledDataSources, final String schemaName) {
        masterSlaveRule.getSingleDataSourceRule().getSlaveDataSourceNames().forEach(each -> {
            if (disabledDataSources.contains(Joiner.on(".").join(schemaName, each))) {
                masterSlaveRule.updateRuleStatus(new DataSourceNameDisabledEvent(each, true));
            }
        });
    }
}
