package org.elasticsearch.river.aq;

import java.util.Properties;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;

import oracle.jms.AQjmsFactory;

public class OracleAqSender {
	private ConnectionFactory connectionFactory;
	private Connection connection;
	private Session session;
	private MessageProducer producer;
	private Destination destination;
	private TextMessage msg;

	public OracleAqSender(String sourceType, String sourceName, 
			String jdbcUrl, String user, String password) throws JMSException {
		
		Properties props = new Properties();
		props.put("user", user);
		props.put("password", password);

		connectionFactory = AQjmsFactory.getConnectionFactory(jdbcUrl, props);
		connection = connectionFactory.createConnection();
		session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

		if (sourceType.equals("queue")) {
			destination = session.createQueue(sourceName);
		} 
		else {
			destination = session.createTopic(sourceName);
		}

		producer = session.createProducer(destination);
		msg = session.createTextMessage();
		connection.start();
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
