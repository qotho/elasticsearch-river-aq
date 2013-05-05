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

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import javax.jms.JMSException;

import org.elasticsearch.action.ListenableActionFuture;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.indices.IndexMissingException;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author Steve Sarandos
 */
public class OracleAqRiverTest {
	private static Properties props;

    final String message = "{ \"index\" : { \"_index\" : \"test\", \"_type\" : \"type1\", \"_id\" : \"1\" }\n" +
            "{ \"type1\" : { \"field1\" : \"value1\" } }\n" +
            "{ \"delete\" : { \"_index\" : \"test\", \"_type\" : \"type1\", \"_id\" : \"2\" } }\n" +
            "{ \"create\" : { \"_index\" : \"test\", \"_type\" : \"type1\", \"_id\" : \"1\" }\n" +
            "{ \"type1\" : { \"field1\" : \"value1\" } }";

    OracleAqClient sender;
    Client client;
    
    static {
    	props = new Properties();
        InputStream in = OracleAqRiverTest.class.getResourceAsStream("test.properties");
        
        try { 
        	props.load(in);            
        	props.setProperty("pass", props.getProperty("password"));
        } 
        catch (Exception e) { 
        	Assert.fail("Failed to load properties: " + e.getMessage());
        } 
        finally {
        	try {
				in.close();
			} 
        	catch (IOException e) {
				e.printStackTrace();
			}
        }
    }


    private void startAQSender() throws Exception {
        sender = new OracleAqClient(props);
        sender.setupTopic();
    }

    private void stopAQSender() throws Exception {
        sender.close();
    }

    private void startElasticSearchInstance() throws IOException {
        Node node = NodeBuilder.nodeBuilder().settings(
          	ImmutableSettings.settingsBuilder().put("gateway.type", "none")).node();

        client = node.client();
        client.prepareIndex("_river", "test1", "_meta").setSource(
        		jsonBuilder()
        			.startObject()
        				.field("type", "aq")
        				.field("aq", props)
        			.endObject()
        		).execute().actionGet();
    }

    private void stopElasticSearchInstance() {
        System.out.println("shutting down elasticsearch");
        client.admin().cluster().prepareNodesShutdown().execute();
        client.close();
    }

    @Test
    public void testSimpleScenario() throws Exception {
        startAQSender();
        startElasticSearchInstance();

        // assure that the index is not yet there
        try {
            ListenableActionFuture<GetResponse> future = 
            		client.prepareGet("test", "type1", "1").execute();
            future.actionGet();
            Assert.fail();
        } 
        catch (IndexMissingException idxExcp) {

        }

        Thread.sleep(3000l);

        try {
            sender.send(message);
            sender.close();
        } 
        catch (JMSException e) {
            Assert.fail("JMS Exception");
        }

        Thread.sleep(3000l);

        {
            ListenableActionFuture<GetResponse> future = 
            		client.prepareGet("test", "type1", "1").execute();
            Object o = future.actionGet();
            GetResponse resp = (GetResponse) o;
            Assert.assertEquals("{ \"type1\" : { \"field1\" : \"value1\" } }", resp.getSourceAsString());
        }


        stopElasticSearchInstance();
        stopAQSender();

        Thread.sleep(3000l);
    }
}
