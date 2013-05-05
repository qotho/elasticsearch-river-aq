/*
 * Licensed to ElasticSearch under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.river.aq;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;

import oracle.jms.AQjmsFactory;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;

/**
 * Oracle Advanced Queuing (AQ) River for ElasticSearch.
 *
 * @author Steve Sarandos
  */
public class OracleAqRiver extends AbstractRiverComponent implements River {

    public final String defaultUser = null;
    public final String defaultPassword = null;
    public static final String defaultSourceType = "queue"; // topic
    public static final String defaultSourceName = "elasticsearch";
    public final String defaultConsumerName;
    public final boolean defaultCreateDurableConsumer = false;
    public final String defaultTopicFilterExpression = "";

    private String user;
    private String password;
    private String jdbcUrl;
    private String sourceType;
    private String sourceName;
    private String consumerName;
    private boolean createDurableConsumer;
    private String topicFilterExpression;


    private final Client client;

    private final int bulkSize;
    private final TimeValue bulkTimeout;
    private final boolean ordered;

    private volatile boolean closed = false;

    private volatile Thread thread;

    private volatile ConnectionFactory connectionFactory;

    @SuppressWarnings({"unchecked"})
    @Inject
    public OracleAqRiver(RiverName riverName, RiverSettings settings, Client client) {
        super(riverName, settings);
        this.client = client;
        this.defaultConsumerName = "aq_elasticsearch_river_" + riverName().name();

        if (settings.settings().containsKey("aq")) {
            Map<String, Object> aqSettings = (Map<String, Object>) settings.settings().get("aq");
            user = XContentMapValues.nodeStringValue(aqSettings.get("user"), defaultUser);
            password = XContentMapValues.nodeStringValue(aqSettings.get("pass"), defaultPassword);
            jdbcUrl = XContentMapValues.nodeStringValue(aqSettings.get("jdbcUrl"), null);
            sourceType = XContentMapValues.nodeStringValue(aqSettings.get("sourceType"), defaultSourceType);
            sourceType = sourceType.toLowerCase();
            
            if (!"queue".equals(sourceType) && !"topic".equals(sourceType)) {
                throw new IllegalArgumentException("Specified an invalid source type for the AQ River. Please specify either 'queue' or 'topic'");
            }
            
            sourceName = XContentMapValues.nodeStringValue(aqSettings.get("sourceName"), defaultSourceName);
            consumerName = XContentMapValues.nodeStringValue(aqSettings.get("consumerName"), defaultConsumerName);
            createDurableConsumer = XContentMapValues.nodeBooleanValue(aqSettings.get("durable"), defaultCreateDurableConsumer);
            topicFilterExpression = XContentMapValues.nodeStringValue(aqSettings.get("filter"), defaultTopicFilterExpression);

        } 
        else {
            user = defaultUser;
            password = defaultPassword;
            jdbcUrl = null;
            sourceType = defaultSourceType;
            sourceName = defaultSourceName;
            consumerName = defaultConsumerName;
            createDurableConsumer = defaultCreateDurableConsumer;
            topicFilterExpression = defaultTopicFilterExpression;
        }

        if (settings.settings().containsKey("index")) {
            Map<String, Object> indexSettings = (Map<String, Object>) settings.settings().get("index");
            bulkSize = XContentMapValues.nodeIntegerValue(indexSettings.get("bulkSize"), 100);
            
            if (indexSettings.containsKey("bulkTimeout")) {
                bulkTimeout = TimeValue.parseTimeValue(XContentMapValues.nodeStringValue(
                		indexSettings.get("bulkTimeout"), "10s"), TimeValue.timeValueMillis(10000));
            } 
            else {
                bulkTimeout = TimeValue.timeValueMillis(10);
            }
            
            ordered = XContentMapValues.nodeBooleanValue(indexSettings.get("ordered"), false);
        } 
        else {
            bulkSize = 100;
            bulkTimeout = TimeValue.timeValueMillis(10);
            ordered = false;
        }
    }


    @Override
    public void start() {
        logger.info("Creating an Oracle AQ river: user [{}], sourceType [{}], sourceName [{}]",
                user, sourceType, sourceName
        );
        
        Properties props = new Properties();
        props.put("user", user);
        props.put("password", password);
        
        try {
          connectionFactory = AQjmsFactory.getConnectionFactory(jdbcUrl, props);
        } 
        catch (JMSException ne) {            
        	throw new ElasticSearchException("Unable to create connection factory.", ne);
        }

        thread = EsExecutors.daemonThreadFactory(settings.globalSettings(), "aq_river").newThread(new Consumer());
        thread.start();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        
        logger.info("Closing the AQ river");
        closed = true;
        thread.interrupt();
    }

    private class Consumer implements Runnable {
        private Connection connection;
        private Session session;
        private Destination destination;

        @Override
        public void run() {
            while (true) {
                if (closed) {
                    break;
                }
                
                try {
                    connection = connectionFactory.createConnection();
                    connection.setClientID(consumerName);
                    session = connection.createSession(false, Session.CLIENT_ACKNOWLEDGE);

                    if (sourceType.equals("queue")) {
                        destination = session.createQueue(sourceName);
                    } 
                    else {
                        destination = session.createTopic(sourceName);
                    }
                } 
                catch (Exception e) {
                    if (!closed) {
                        logger.warn("failed to created a connection / channel", e);
                    } 
                    else {
                        continue;
                    }
                    
                    cleanup(0, "failed to connect");
                    
                    try {
                        Thread.sleep(5000);
                    } 
                    catch (InterruptedException e1) {
                        // ignore, if we are closing, we will exit later
                    }
                }

                // define the queue
                MessageConsumer consumer = null;
                
                try {
                    if (createDurableConsumer && "topic".equals(sourceType)) {
                        if (topicFilterExpression != null && topicFilterExpression.length() > 0) {
                            consumer = session.createDurableSubscriber(
                                    (Topic) destination, consumerName, topicFilterExpression, true
                            );
                        } 
                        else {
                            consumer = session.createDurableSubscriber((Topic) destination, consumerName);
                        }
                    } 
                    else {
                        consumer = session.createConsumer(destination);
                    }

                } 
                catch (Exception e) {
                    if (!closed) {
                        logger.warn("failed to create queue/topic [{}]", e, sourceName);
                    }
                    cleanup(0, "failed to create queue");
                    continue;
                }

                try {
                    connection.start();
                } 
                catch (JMSException e) {
                    cleanup(5, "failed to start connection");
                }


                // now use the queue/topic to listen for messages
                while (true) {
                    if (closed) {
                        break;
                    }
                    
                    Message message = null;
                    
                    try {
                        message = receiveNextMessage(consumer, 0);
                    } 
                    catch (Exception e) {
                        if (!closed) {
                            logger.error("failed to get next message, reconnecting...", e);
                        }
                        
                        cleanup(0, "failed to get message");
                        break;
                    }
                                        
                    if (message != null && message instanceof TextMessage) {
                 		if (logger.isInfoEnabled()) {
                 			logger.info("got a message [{}]", message);
                 		}

                        final List<Message> messages = Lists.newArrayList();
                        byte[] msgContent;
                        
                        try {
                            msgContent = getMessageContent((TextMessage) message);
                        } 
                        catch (Exception e) {
                            logger.warn("failed to get message content for delivery tag [{}], ack'ing...", e, getMessageID(message));
                            acknowledgeMessage(message);
                            continue;
                        }

                        BulkRequestBuilder bulkRequestBuilder = client.prepareBulk();

                        try {
                            bulkRequestBuilder.add(msgContent, 0, msgContent.length, false);
                        } 
                        catch (Exception e) {
                            logger.warn("failed to parse request for delivery tag [{}], ack'ing...", e, getMessageID(message));
                            acknowledgeMessage(message);
                            continue;
                        }

                        // Save for later acknowledgment.
                        messages.add(message);

                        if (bulkRequestBuilder.numberOfActions() < bulkSize) {
                            // try and spin some more of those without timeout, so we have a bigger bulk (bounded by the bulk size)
                            while ((message = receiveNextMessage(consumer, bulkTimeout.millis())) != null) {
                                try {
                                    msgContent = getMessageContent(message);
                                    bulkRequestBuilder.add(msgContent, 0, msgContent.length, false);
                                    messages.add(message);
                                } 
                                catch (Exception e) {
                                    logger.warn("failed to parse request for message ID [{}], ack'ing...", e, getMessageID(message));
                                    acknowledgeMessage(message);
                                }
                                
                                if (bulkRequestBuilder.numberOfActions() >= bulkSize) {
                                    break;
                                }
                            }
                        }

                        if (logger.isTraceEnabled()) {
                            logger.trace("executing bulk with [{}] actions", bulkRequestBuilder.numberOfActions());
                        }

                        if (ordered) {
                            try {
                                BulkResponse response = bulkRequestBuilder.execute().actionGet();
                                
                                if (response.hasFailures()) {
                                    // TODO write to exception queue?
                                    logger.warn("failed to execute" + response.buildFailureMessage());
                                }
                                
                                for (Message msg : messages) {
                                	acknowledgeMessage(msg);
                                }
                            } 
                            catch (Exception e) {
                                logger.warn("failed to execute bulk", e);
                            }
                        } 
                        else {
                            bulkRequestBuilder.execute(new ActionListener<BulkResponse>() {
                                @Override
                                public void onResponse(BulkResponse response) {
                                    if (response.hasFailures()) {
                                        // TODO write to exception queue?
                                        logger.warn("failed to execute" + response.buildFailureMessage());
                                    }
                                    
                                    for (Message message : messages) {
                                    	acknowledgeMessage(message);
                                    }
                                }

                                @Override
                                public void onFailure(Throwable e) {
                                    logger.warn("failed to execute bulk for delivery tags [{}], not ack'ing", e, messages);
                                }
                            });
                        }
                    } 
                }
            }
            
            cleanup(0, "closing river");
        }

        private Message receiveNextMessage(MessageConsumer consumer, long timeout) {
        	Message msg = null;
        	
        	try {
				msg = consumer.receive(timeout);
			} 
        	catch (JMSException e) {
                logger.warn("failed to retrieve next message.", e);
			}
        	
        	return msg;
        }
        
        private void acknowledgeMessage(Message msg) {
           try {
               msg.acknowledge();
           } 
           catch (JMSException e) {
               logger.warn("failed to ack [{}]", e, getMessageID(msg));
           }         
        }
        
        private byte[] getMessageContent(Message msg) throws JMSException {
        	String text = ((TextMessage) msg).getText();         
        	byte[] content = null;
         
         	// Body no longer needed. Save some memory on large batches.
         	msg.clearBody();
         
         	if (text != null && text.length() > 0) {
         		if (logger.isTraceEnabled()) {
         			logger.trace("message was [{}]", text);
         		}
          		content = text.getBytes();
         	}
         
         	return content;
        }

        private String getMessageID(Message msg) {
          	if (msg == null) {
           		return null;
          	}
          
          	String msgId = null;
          
          	try {
           		msgId = msg.getJMSMessageID();
          	}
          	catch (JMSException e) {
           		logger.warn("failed to get JMS Message ID", e);
           		msgId = null;
          	}
          
          	return msgId;
        }

        private void cleanup(int code, String message) {
            try {
                session.close();
            } 
            catch (Exception e) {
                logger.debug("failed to close session on [{}]", e, message);
            }

            try {
                connection.stop();
            } 
            catch (JMSException e) {
                logger.debug("failed to stop connection on [{}]", e);
            }

            try {
                connection.close();
            } 
            catch (Exception e) {
                logger.debug("failed to close connection on [{}]", e, message);
            }
        }
    }

}
