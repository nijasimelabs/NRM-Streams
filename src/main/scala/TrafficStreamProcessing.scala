import java.util.Properties
import org.apache.kafka.connect.json.JsonDeserializer;
import org.apache.kafka.connect.json.JsonSerializer;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.kafka.common.serialization._
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization._
import org.apache.kafka.streams.kstream.{Printed, KStream, KTable, Produced, Serialized, ForeachAction}
import org.apache.kafka.streams.kstream.ValueJoiner
import org.apache.kafka.streams._
import scala.collection.JavaConverters._
import collection.mutable.Map
import java.util.function.Consumer;
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}


import com.fasterxml.jackson.databind.node.{JsonNodeFactory, ObjectNode, ArrayNode, TextNode};


object TrafficStreamProcessing {
  def main(args: Array[String]): Unit = {

    val wanOperationalDbTopic: String = "wan_op_db"
    val trafficTopic: String = "traffic"
    val operationalSCPCTopic: String = "operational_scpc"
    val result_stream_key = "channel_group_profile"
    val result_stream_topic = "channel_group_profile_topic"
    val opscpcKey = "opscpc"
    val wandbKey = "wandb"

    val SPOKE = "spoke"
    val REMOTE= "remotename"
    val CHANNEL_GROUPS= "channel_groups"
    val GROUP_NAME = "groupname"
    val GROUP_MEMBERS = "group_members"
    val ChannelGroupProfile = "changroup_profile"

    val stringSerde: Serde[String] = Serdes.String()
    val jsonSerializer: Serializer[JsonNode] = new JsonSerializer()
    val jsonDeserializer: Deserializer[JsonNode] = new JsonDeserializer()
    val jsonSerde: Serde[JsonNode] = Serdes.serdeFrom(jsonSerializer, jsonDeserializer)


    val config = {
      val properties = new Properties()
      properties.put(StreamsConfig.APPLICATION_ID_CONFIG, "stream-application")
      properties.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
      properties.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass)
      properties.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass)
      properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
      properties
    }

    val props = new Properties()
    props.put("bootstrap.servers", "localhost:9092")
    props.put("client.id", "ScalaProducerExample")
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")

    val producer = new KafkaProducer[String, String](props)
    val builder: StreamsBuilder = new StreamsBuilder()
    val aggregate_values: ObjectNode = JsonNodeFactory.instance.objectNode();
    var wandbstore = Map[String, Array[String]]()
    val store: ObjectNode = JsonNodeFactory.instance.objectNode();


    //define stream here
    val operationalStream: KStream[String, JsonNode] = builder.stream(operationalSCPCTopic, Consumed.`with`(stringSerde, jsonSerde))
    val wandbStream: KStream[String, JsonNode] = builder.stream(wanOperationalDbTopic, Consumed.`with`(stringSerde, jsonSerde))
    val trafficStream: KStream[String, JsonNode] = builder.stream(trafficTopic, Consumed.`with`(stringSerde, jsonSerde));


    operationalStream.foreach(
      new ForeachAction[String, JsonNode]() {
        override def apply(key: String, value: JsonNode): Unit = {
          store.set(opscpcKey, value)
          var sum_ip_rate:Double = 0;
          for (i <- 0 until value.size()){
            sum_ip_rate = sum_ip_rate+ value.get(0).get("avaiableIPrate").asDouble()
          }
          aggregate_values.set("Total_available_iprate", JsonNodeFactory.instance.numberNode(sum_ip_rate))
        }
      }
    );


    wandbStream.foreach(
      new ForeachAction[String, JsonNode]() {
        override def apply(key: String, value: JsonNode): Unit = {
          val wanlinks = value.get("links")
          for(wlink <- wanlinks.elements.asScala) {
            val linkName = wlink.get("key").asText
            var dscps = Array[String]()
            for(item <- wlink.get("dscp_value").elements.asScala) {
              dscps = dscps :+ item.asText
            }
            wandbstore = wandbstore + (linkName -> dscps)
          }
        }
      }
    );


    // processing the stream for aggregation
    trafficStream.foreach(
      new ForeachAction[String, JsonNode]() {
        override def apply(key: String, value: JsonNode): Unit = {

          for( x <- 0 until value.size()) {

            // calculate agrgegate cir, mir for each link
            // cir should be the sum of cir and max of mir
            var max_mir:Double = 0.0;
            var sum_cir:Double = 0.0;
            var link_name = value.get(x).get("link").asText();
            val link:ObjectNode = JsonNodeFactory.instance.objectNode();

            var trafficClasses = value.get(x).get("trafficclass")
            val dscpValues = wandbstore.getOrElse(link_name, Array[String]())
            val filteredClasses = trafficClasses.elements.asScala.filter(
              tclass => {
                dscpValues.exists(
                  i => {
                    tclass.get("expression").asText().indexOf("dscp " + i) != -1
                  })
              }
            )
            val updatedTrafficClasses = JsonNodeFactory.instance.arrayNode()
            for(item  <- filteredClasses) {
              updatedTrafficClasses.add(item)
            }

            // update trafficClasses
            value.get(x).asInstanceOf[ObjectNode].set("trafficclass", updatedTrafficClasses)

            for (tclass <- value.get(x).get("trafficclass").elements.asScala) {
              val cir = tclass.get("cir").asDouble();
              val mir = tclass.get("mir").asDouble()
              sum_cir = sum_cir + cir
              if (max_mir < mir){
                max_mir = mir;
              }
            }

            // json node for cir mir aggregate
            link.put("cir", sum_cir * 1000000)
            link.put("mir", max_mir * 1000000)
            aggregate_values.set(link_name, link)
          }

          // TODO: filter links with dscp values
          val rootNode = JsonNodeFactory.instance.objectNode()
          val profiles = JsonNodeFactory.instance.arrayNode()
          val result: ObjectNode = JsonNodeFactory.instance.objectNode()
          // FIXME: check if required
          result.set(SPOKE, value.get(REMOTE))
          val channelGroups: ArrayNode = JsonNodeFactory.instance.arrayNode();

          //looping again to push the data to final data structure
          for(i <- 0 until value.size()){

            val linkName = value.get(i).get("link").asText();
            val channel = JsonNodeFactory.instance.objectNode();
            val groupname: TextNode = JsonNodeFactory.instance.textNode(linkName);
            channel.set(GROUP_NAME, groupname);
            val cir = List(aggregate_values.get(linkName).get("cir").asDouble(), channelIpRate(store.get(opscpcKey), linkName)).min
            val mir = List(aggregate_values.get(linkName).get("mir").asDouble(), channelIpRate(store.get(opscpcKey), linkName)).min
            channel.put("cir", cir);
            channel.put("mir", mir);
            val members = JsonNodeFactory.instance.arrayNode()

            for (j <- 0 until value.get(i).get("trafficclass").size()){

              val channelObj =  JsonNodeFactory.instance.objectNode();
              val channelName: TextNode = JsonNodeFactory.instance.textNode(value.get(i).get("trafficclass").get(j).get("name").asText());
              channelObj.set("chan_name", channelName)

              val weight = calculateWeight(
                value.get(i).get("trafficclass").get(j).get("cir").asDouble(),
                aggregate_values.get(linkName).get("cir").asDouble(),
                aggregate_values.get("Total_available_iprate").asDouble(),
                channelIpRate(store.get(opscpcKey), linkName)
              )

              channelObj.put("weight", weight)
              channelObj.put("mir", value.get(i).get("trafficclass").get(j).get("mir").asDouble())
              members.add(channelObj);
            }
            channel.set("channel_group", members);
            channelGroups.add(channel)

          }

          result.set(CHANNEL_GROUPS, channelGroups)
          profiles.add(result)
          rootNode.set(ChannelGroupProfile, profiles)
          val data = new ProducerRecord[String, String](result_stream_topic, result_stream_key, rootNode.toString())
          producer.send(data)

        }
      });


    val streamApp : KafkaStreams = new KafkaStreams(builder.build(), config)
    streamApp.start();
  }



  def calculateWeight(channel_cir:Double, aggregate_cir:Double, total_ip_rate:Double, channel_iprate:Double):Double={
    val weight =  (channel_cir/aggregate_cir)*(total_ip_rate/channel_iprate)*100;
    return weight;
  }

  def channelIpRate(operationalScpc:JsonNode, linkName:String):Double={
    var iprate:Double = 0.0;
    for ( i <- 0 until operationalScpc.size()){
      if(operationalScpc.get(i).get("linkname").asText().equals(linkName)){
        iprate = operationalScpc.get(i).get("avaiableIPrate").asDouble();
      }
    }

    return iprate;
  }
}
