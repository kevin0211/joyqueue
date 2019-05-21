/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jd.journalq.broker.handler;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.jd.journalq.broker.JournalqCommandHandler;
import com.jd.journalq.broker.JournalqContext;
import com.jd.journalq.broker.JournalqContextAware;
import com.jd.journalq.broker.cluster.ClusterManager;
import com.jd.journalq.broker.command.ProduceMessageAck;
import com.jd.journalq.broker.config.JournalqConfig;
import com.jd.journalq.broker.converter.CheckResultConverter;
import com.jd.journalq.broker.helper.SessionHelper;
import com.jd.journalq.broker.network.traffic.Traffic;
import com.jd.journalq.broker.producer.Produce;
import com.jd.journalq.broker.producer.ProduceConfig;
import com.jd.journalq.domain.QosLevel;
import com.jd.journalq.domain.TopicName;
import com.jd.journalq.exception.JournalqCode;
import com.jd.journalq.exception.JournalqException;
import com.jd.journalq.message.BrokerMessage;
import com.jd.journalq.network.command.BooleanAck;
import com.jd.journalq.network.command.JournalqCommandType;
import com.jd.journalq.network.command.ProduceMessage;
import com.jd.journalq.network.command.ProduceMessageAckData;
import com.jd.journalq.network.command.ProduceMessageAckItemData;
import com.jd.journalq.network.command.ProduceMessageData;
import com.jd.journalq.network.session.Connection;
import com.jd.journalq.network.session.Producer;
import com.jd.journalq.network.transport.Transport;
import com.jd.journalq.network.transport.command.Command;
import com.jd.journalq.network.transport.command.Type;
import com.jd.journalq.response.BooleanResponse;
import com.jd.journalq.store.WriteResult;
import com.jd.journalq.toolkit.concurrent.EventListener;
import com.jd.journalq.toolkit.time.SystemClock;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ProduceMessageHandler
 * author: gaohaoxiang
 * email: gaohaoxiang@jd.com
 * date: 2018/12/19
 */
public class ProduceMessageHandler implements JournalqCommandHandler, Type, JournalqContextAware {

    protected static final Logger logger = LoggerFactory.getLogger(ProduceMessageHandler.class);

    private JournalqConfig config;
    private ProduceConfig produceConfig;
    private Produce produce;
    private ClusterManager clusterManager;

    @Override
    public void setJmqContext(JournalqContext journalqContext) {
        this.config = journalqContext.getConfig();
        this.produceConfig = new ProduceConfig(journalqContext.getBrokerContext().getPropertySupplier());
        this.produce = journalqContext.getBrokerContext().getProduce();
        this.clusterManager= journalqContext.getBrokerContext().getClusterManager();
    }

    @Override
    public Command handle(Transport transport, Command command) {
        ProduceMessage produceMessage = (ProduceMessage) command.getPayload();
        Connection connection = SessionHelper.getConnection(transport);

        if (connection == null || !connection.isAuthorized(produceMessage.getApp())) {
            logger.warn("connection is not exists, transport: {}", transport);
            return BooleanAck.build(JournalqCode.FW_CONNECTION_NOT_EXISTS.getCode());
        }

        QosLevel qosLevel = command.getHeader().getQosLevel();
        boolean isNeedAck = !qosLevel.equals(QosLevel.ONE_WAY);
        CountDownLatch latch = new CountDownLatch(produceMessage.getData().size());
        Map<String, ProduceMessageAckData> resultData = Maps.newConcurrentMap();
        Traffic traffic = new Traffic(produceMessage.getApp());

        for (Map.Entry<String, ProduceMessageData> entry : produceMessage.getData().entrySet()) {
            String topic = entry.getKey();
            ProduceMessageData produceMessageData = entry.getValue();

            // 校验
            try {
                checkAndFillProduceMessage(connection, produceMessageData);
            } catch (JournalqException e) {
                logger.warn("checkMessage error, transport: {}, topic: {}, app: {}", transport, topic, produceMessage.getApp(), e);
                resultData.put(topic, buildProduceMessageAckData(produceMessageData, JournalqCode.valueOf(e.getCode())));
                latch.countDown();
                continue;
            }

            BooleanResponse checkResult = clusterManager.checkWritable(TopicName.parse(topic), produceMessage.getApp(), connection.getHost(), produceMessageData.getMessages().get(0).getPartition());
            if (!checkResult.isSuccess()) {
                logger.warn("checkWritable failed, transport: {}, topic: {}, app: {}, code: {}", transport, topic, produceMessage.getApp(), checkResult.getJournalqCode());
                resultData.put(topic, buildProduceMessageAckData(produceMessageData, CheckResultConverter.convertProduceCode(checkResult.getJournalqCode())));
                latch.countDown();
                continue;
            }

            produceMessage(connection, topic, produceMessage.getApp(), produceMessageData, (data) -> {
                resultData.put(topic, data);
                traffic.record(topic, produceMessageData.getSize());
                latch.countDown();
            });
        }

        if (!isNeedAck) {
            return null;
        }

        try {
            boolean isDone = latch.await(config.getProduceMaxTimeout(), TimeUnit.MILLISECONDS);
            if (!isDone) {
                logger.warn("wait produce timeout, transport: {}, topics: {}", transport.remoteAddress(), produceMessage.getData().keySet());
            }
        } catch (InterruptedException e) {
            logger.error("wait produce exception, transport: {}", transport.remoteAddress(), e);
        }

        ProduceMessageAck produceMessageAck = new ProduceMessageAck();
        produceMessageAck.setTraffic(traffic);
        produceMessageAck.setData(resultData);
        return new Command(produceMessageAck);
    }

    protected void produceMessage(Connection connection, String topic, String app, ProduceMessageData produceMessageData, EventListener<ProduceMessageAckData> listener) {
        Producer producer = new Producer(connection.getId(), topic, app, Producer.ProducerType.JMQ);
        try {
            produce.putMessageAsync(producer, produceMessageData.getMessages(), produceMessageData.getQosLevel(), produceMessageData.getTimeout(), (writeResult) -> {
                if (!writeResult.getCode().equals(JournalqCode.SUCCESS)) {
                    logger.error("produce message failed, topic: {}, code: {}", producer.getTopic(), writeResult.getCode());
                }
                ProduceMessageAckData produceMessageAckData = new ProduceMessageAckData();
                produceMessageAckData.setCode(writeResult.getCode());
                produceMessageAckData.setItem(buildProduceMessageAckItemData(produceMessageData.getMessages(), writeResult));
                listener.onEvent(produceMessageAckData);
            });
        } catch (JournalqException e) {
            logger.error("produceMessage exception, transport: {}, topic: {}, app: {}", connection.getTransport().remoteAddress(), topic, app, e);
            listener.onEvent(buildProduceMessageAckData(produceMessageData, JournalqCode.valueOf(e.getCode())));
        } catch (Exception e) {
            logger.error("produceMessage exception, transport: {}, topic: {}, app: {}", connection.getTransport().remoteAddress(), topic, app, e);
            listener.onEvent(buildProduceMessageAckData(produceMessageData, JournalqCode.CN_UNKNOWN_ERROR));
        }
    }

    protected List<ProduceMessageAckItemData> buildProduceMessageAckItemData(List<BrokerMessage> messages, WriteResult writeResult) {
        List<ProduceMessageAckItemData> item = Lists.newArrayListWithCapacity(messages.size());
        if (ArrayUtils.isEmpty(writeResult.getIndices())) {
            for (BrokerMessage message : messages) {
                item.add(new ProduceMessageAckItemData(message.getPartition(), ProduceMessageAckItemData.INVALID_INDEX, message.getStartTime()));
            }
        } else {
            long now = SystemClock.now();
            for (int i = 0; i < writeResult.getIndices().length; i++) {
                BrokerMessage brokerMessage = messages.get(i);
                item.add(new ProduceMessageAckItemData(brokerMessage.getPartition(), writeResult.getIndices()[i], now));
            }
        }
        return item;
    }

    protected void checkAndFillProduceMessage(Connection connection, ProduceMessageData produceMessageData) throws JournalqException {
        if (CollectionUtils.isEmpty(produceMessageData.getMessages())) {
            throw new JournalqException(JournalqCode.CN_PARAM_ERROR, "messages not empty");
        }
        byte[] address = connection.getAddress();
        String txId = produceMessageData.getTxId();
        int partition = produceMessageData.getMessages().get(0).getPartition();
        for (BrokerMessage brokerMessage : produceMessageData.getMessages()) {
            if (brokerMessage.getPartition() != partition) {
                throw new JournalqException(JournalqCode.CN_PARAM_ERROR, "the put message command has multi partition");
            }
            if (StringUtils.length(brokerMessage.getBusinessId()) > produceConfig.getBusinessIdLength()) {
                throw new JournalqException(JournalqCode.CN_PARAM_ERROR, "message businessId out of rage");
            }
            brokerMessage.setClientIp(address);
            brokerMessage.setTxId(txId);
        }
    }

    protected ProduceMessageAckData buildProduceMessageAckData(ProduceMessageData produceMessageData, JournalqCode code) {
        List<ProduceMessageAckItemData> item = Lists.newArrayListWithCapacity(produceMessageData.getMessages().size());
        for (int i = 0; i < produceMessageData.getMessages().size(); i++) {
            item.add(ProduceMessageAckItemData.INVALID_INSTANCE);
        }
        return new ProduceMessageAckData(item, code);
    }

    @Override
    public int type() {
        return JournalqCommandType.PRODUCE_MESSAGE.getCode();
    }
}