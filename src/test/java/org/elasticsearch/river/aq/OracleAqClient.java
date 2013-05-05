package org.elasticsearch.river.aq;

import java.util.Properties;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;

import oracle.AQ.AQQueueTable;
import oracle.AQ.AQQueueTableProperty;
import oracle.jms.AQjmsDestination;
import oracle.jms.AQjmsDestinationProperty;
import oracle.jms.AQjmsFactory;
import oracle.jms.AQjmsSession;

public class OracleAqClient {
	private ConnectionFactory connectionFactory;
	private Connection connection;
	private Session session;
	private MessageProducer producer;
	private Destination destination;
	private TextMessage msg;
	private Properties props;

	public OracleAqClient(Properties props) throws JMSException {
		this.props = props;
		connectionFactory = AQjmsFactory.getConnectionFactory(props.getProperty("jdbcUrl"), props);
		connection = connectionFactory.createConnection();
		session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

		if (props.getProperty("sourceType").equals("queue")) {
			destination = session.createQueue(props.getProperty("sourceName"));
		} 
		else {
			destination = session.createTopic(props.getProperty("sourceName"));
		}

		producer = session.createProducer(destination);
		msg = session.createTextMessage();
		connection.start();
	}

	public void setupTopic() throws Exception {
		AQQueueTableProperty qtprop;
		AQQueueTable qtable;
		AQjmsDestinationProperty dprop;
		Topic topic;
		
		try {
			/* Create Queue Tables */
			System.out.println("Creating Input Queue Table...");

			/* Drop the queue if already exists */
			try {
				qtable = ((AQjmsSession) session).getQueueTable(props.getProperty("user"), "jmsqtable");
				qtable.drop(true);
			} 
			catch (Exception e) {
			}

			qtprop = new AQQueueTableProperty("SYS.AQ$_JMS_TEXT_MESSAGE");
			qtprop.setMultiConsumer(true);
			// qtprop.setCompatible("8.1") ;
			qtprop.setPayloadType("SYS.AQ$_JMS_TEXT_MESSAGE");
			qtable = ((AQjmsSession) session)
					.createQueueTable(props.getProperty("user"), "jmsqtable", qtprop);

			System.out.println("Creating Topic input_queue...");
			dprop = new AQjmsDestinationProperty();
			topic = ((AQjmsSession) session).createTopic(qtable, "jmstopic", dprop);

			session.createDurableSubscriber(topic, props.getProperty("consumerName"));
			
			/* Start the topic */
			((AQjmsDestination) topic).start(session, true, true);
			System.out.println("Successfully setup Topic");
		} 
		catch (Exception ex) {
			System.out.println("Error in setupTopic: " + ex);
			throw ex;
		}
	}
	
	public void send(String message) throws JMSException {
		msg.setText(message);
		producer.send(msg);
	}

	public void close() throws JMSException {
		producer.close();
		session.close();
		connection.close();
	}

}
