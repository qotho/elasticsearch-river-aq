Oracle Advanced Queuing (AQ) River Plugin for ElasticSearch
===========================================================

The Oracle AQ River provides a means of submitting bulk indexing requests to elasticsearch using an Oracle database queue.  Using this river, there is no need to configure a separate JMS queue in an application server that wraps the database queue.  This river uses the Oracle AQ JMS API to directly connect to the database and queue through JDBC.

The format of the messages follows the elasticsearch bulk API format:

	{ "index" : { "_index" : "twitter", "_type" : "tweet", "_id" : "1" } }
	{ "tweet" : { "text" : "this is a tweet" } }
	{ "delete" : { "_index" : "twitter", "_type" : "tweet", "_id" : "2" } }
	{ "create" : { "_index" : "twitter", "_type" : "tweet", "_id" : "1" } }
	{ "tweet" : { "text" : "another tweet" } }    

The river automatically batches queue messages.  It reads messages from the queue until it either has the maximum number of messages configured by the bulkSize setting, or the bulkTimeout has been reached and no more messages are in the queue. All collected messages are submitted as a single bulk request.

Installation
------------
Creating the river in elasticsearch is as simple as:

	curl -XPUT 'localhost:9200/_river/my_river/_meta' -d '{
	    "type" : "aq",
	    "aq" : {
	        "jdbcUrl" : "jdbc:oracle:thin:@dbserver:1521:orcl", 
	        "user" : "aquser",
	        "pass" : "aquser",
	        "sourceType" : "topic",
	        "sourceName" : "jmstopic",
	        "consumerName" : "elasticsearch",
	        "durable" : true,
	        "filter" : "JMSCorrelationID = 'someid'"
	    },
	    "index" : {
	        "bulkSize" : 100,
	        "bulkTimeout" : "10ms",
	        "ordered" : false
	    }
	}'
	
Configuration Settings
----------------------

- jdbcUrl: JDBC URL to connect to the database where queue/topic resides.
- user: The user name to be used for a secure JNDI connection.
- pass: The password to be used for a secure JNDI connection.
- sourceType: Indicates whether the source is either a "queue" or "topic". 
- sourceName: The queue or topic name.
- consumerName: The name of the consumer or subscriber.
- durable: Indicates whether the consumer is a durable subscriber.  This option is only available got topics.
- filter: A message selector to only dequeue messages that match the given expression.
- bulkSize: The maximum batch size (bulk actions) the river will submit to elasticsearch.
- bulkTimeout: The length of time the river will wait for new messages to add to a batch.
- ordered: Indicates whether the river should submit the bulk actions in the order it got them from the queue.  This setting can also be used a simple way to throttle indexing.

License
-------

    This software is licensed under the Apache 2 license, quoted below.

    Copyright 2012 ElasticSearch <http://www.elasticsearch.org>

    Licensed under the Apache License, Version 2.0 (the "License"); you may not
    use this file except in compliance with the License. You may obtain a copy of
    the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
    License for the specific language governing permissions and limitations under
    the License.
