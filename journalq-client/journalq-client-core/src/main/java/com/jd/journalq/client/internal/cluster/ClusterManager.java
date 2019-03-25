package com.jd.journalq.client.internal.cluster;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.jd.journalq.client.internal.cluster.domain.TopicMetadataHolder;
import com.jd.journalq.client.internal.metadata.MetadataManager;
import com.jd.journalq.client.internal.metadata.domain.TopicMetadata;
import com.jd.journalq.client.internal.nameserver.NameServerConfig;
import com.jd.journalq.client.internal.nameserver.NameServerConfigChecker;
import com.jd.journalq.toolkit.lang.Preconditions;
import com.jd.journalq.toolkit.service.Service;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * ClusterManager
 * author: gaohaoxiang
 * email: gaohaoxiang@jd.com
 * date: 2018/11/28
 */
public class ClusterManager extends Service {

    protected static final Logger logger = LoggerFactory.getLogger(ClusterManager.class);

    private NameServerConfig config;
    private ClusterClientManager clusterClientManager;

    private MetadataManager metadataManager;
    private MetadataCacheManager metadataCacheManager;
    private MetadataUpdater metadataUpdater;

    public ClusterManager(NameServerConfig config, ClusterClientManager clusterClientManager) {
        NameServerConfigChecker.check(config);
        Preconditions.checkArgument(clusterClientManager != null, "clusterClientManager can not be null");

        this.config = config;
        this.clusterClientManager = clusterClientManager;
    }

    public List<TopicMetadata> fetchTopicMetadataList(List<String> topics, String app) {
        Map<String, TopicMetadata> topicMetadataMap = fetchTopicMetadata(topics, app);
        List<TopicMetadata> result = Lists.newArrayListWithCapacity(topicMetadataMap.size());

        for (Map.Entry<String, TopicMetadata> entry : topicMetadataMap.entrySet()) {
            TopicMetadata topicMetadata = entry.getValue();
            if (topicMetadata != null) {
                result.add(topicMetadata);
            }
        }

        return result;
    }

    public Map<String, TopicMetadata> fetchTopicMetadata(List<String> topics, String app) {
        Map<String, TopicMetadata> result = Maps.newLinkedHashMap();
        List<String> needUpdateTopics = null;

        for (String topic : topics) {
            TopicMetadataHolder topicMetadataHolder = metadataCacheManager.getTopicMetadata(topic, app);
            if (topicMetadataHolder != null) {
                if (topicMetadataHolder.isExpired()) {
                    metadataUpdater.tryUpdateTopicMetadata(topic, app);
                }
                result.put(topic, topicMetadataHolder.getTopicMetadata());
            } else {
                if (needUpdateTopics == null) {
                    needUpdateTopics = Lists.newLinkedList();
                }
                needUpdateTopics.add(topic);
            }
        }

        if (CollectionUtils.isNotEmpty(needUpdateTopics)) {
            Map<String, TopicMetadata> topicMetadataMap = metadataUpdater.updateTopicMetadata(topics, app);
            result.putAll(topicMetadataMap);
        }

        return result;
    }

    public TopicMetadata fetchTopicMetadata(String topic, String app) {
        TopicMetadataHolder topicMetadataHolder = metadataCacheManager.getTopicMetadata(topic, app);
        if (topicMetadataHolder == null) {
            return metadataUpdater.updateTopicMetadata(topic, app);
        }
        if (topicMetadataHolder.isExpired()) {
            metadataUpdater.tryUpdateTopicMetadata(topic, app);
        }
        return topicMetadataHolder.getTopicMetadata();
    }

    public boolean tryUpdateTopicMetadata(String topic, String app) {
        TopicMetadataHolder topicMetadata = metadataCacheManager.getTopicMetadata(topic, app);
        if (topicMetadata != null && !topicMetadata.isExpired(config.getTempMetadataInterval())) {
            return false;
        }
        return metadataUpdater.tryUpdateTopicMetadata(topic, app);
    }

    public TopicMetadata updateTopicMetadata(String topic, String app) {
        return metadataUpdater.updateTopicMetadata(topic, app);
    }

    public Map<String, TopicMetadata> updateTopicMetadata(List<String> topics, String app) {
        return metadataUpdater.updateTopicMetadata(topics, app);
    }

    @Override
    protected void validate() throws Exception {
        metadataCacheManager = new MetadataCacheManager(config);
        metadataManager = new MetadataManager(clusterClientManager);
        metadataUpdater = new MetadataUpdater(config, metadataManager, metadataCacheManager);
    }

    @Override
    protected void doStart() throws Exception {
        metadataUpdater.start();
//        logger.info("clusterManager is started");
    }

    @Override
    protected void doStop() {
        if (metadataUpdater != null) {
            metadataUpdater.stop();
        }
//        logger.info("clusterManager is stopped");
    }
}