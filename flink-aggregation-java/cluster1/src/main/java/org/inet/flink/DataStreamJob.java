package org.inet.flink;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.time.Time;
// import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.functions.windowing.AllWindowFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.functions.ProcessFunction.Context;
import org.apache.flink.streaming.api.functions.windowing.ProcessAllWindowFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.SlidingProcessingTimeWindows;


import java.time.Duration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.io.InputStream;
import java.io.IOException;

import java.util.Properties;

import org.inet.flink.model.Product;
import org.inet.flink.model.Delay;
import org.inet.flink.mapper.JsonToProductMapper;
import org.inet.flink.mapper.ProductToJsonMapper;
import org.inet.flink.generator.DataGenerator;

public class DataStreamJob {

	private static String KAFKA_BOOTSTRAP_SERVERS;
	private static String CONSUMER_TOPIC;
	private static String CLUSTER_COMMUNICATION_TOPIC;

	public static void main(String[] args) throws Exception {
		final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

		// env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
		env.setRestartStrategy(RestartStrategies.fixedDelayRestart(
			2, // Number of restart attempts
			Time.seconds(1L) // Delay between restarts
		));

		// Assigns values to the field variables
		loadProperties();
		
		// Starts data generation
		// DataGenerator dataGenerator = new DataGenerator(KAFKA_BOOTSTRAP_SERVERS);
		// dataGenerator.generateData(CONSUMER_TOPIC);

		// Receives data from data generator
		KafkaSource<String> dataGeneratorSource = createKafkaSource(CONSUMER_TOPIC);
		DataStream<String> streamSource = env.fromSource(dataGeneratorSource,
		WatermarkStrategy.forBoundedOutOfOrderness(Duration.ofSeconds(2)), "Kafka Data Generator");
		// WatermarkStrategy.forMonotonousTimestamps(), "Kafka Data Generator");
		// DataStream<String> streamSource = env.readTextFile("../../../../../../../../records/output.txt");

		KafkaSink<String> kafkaSink = createLocalKafkaSink();

		// Maps strings to product type and filters them by name
		DataStream<Product> products = streamSource
			.map(new JsonToProductMapper())
			.name("Map: Json to Product")
			.filter(product -> product.getName().equals("Lemon"))
			.name("Filter: By Product name Lemon");

		DataStream<Long> countPerWindow = products
			.windowAll(TumblingProcessingTimeWindows.of(org.apache.flink.streaming.api.windowing.time.Time.minutes(1)))
			// .name("1 minute window")
			.apply(new AllWindowFunction<Product, Long, TimeWindow>() {
				public void apply(TimeWindow window, Iterable<Product> products, Collector<Long> out) throws Exception {
					long count = 0;
					for (Product product : products) {
						count++;
					}
					out.collect(count);
				}
			})
			.name("Apply: Counting products");
		
		countPerWindow.print();

		// Prints prices of products
		DataStream<Double> prices = products
			.map(Product::getPrice)
			.name("Map: Extract prices")
			.windowAll(TumblingProcessingTimeWindows.of(org.apache.flink.streaming.api.windowing.time.Time.seconds(1)))
			// .name("1 second window")
			.sum(0)
			.name("Sum: Over the prices")
			.map(sum -> (double) Math.round(sum*100)/100)
			.name("Map: Round to two decimal places");

		prices.print();

		Delay delay = new Delay();

		// DataStream<String> sink = products
		// 	.map(new ProductToJsonMapper())
		// 	.windowAll(SlidingProcessingTimeWindows.of(org.apache.flink.streaming.api.windowing.time.Time.seconds(5), org.apache.flink.streaming.api.windowing.time.Time.seconds(5)))
		// 	.apply(new AllWindowFunction<String, String, TimeWindow>() {
		// 		public void apply(TimeWindow window, Iterable<String> products, Collector<String> out) throws Exception {
		// 			Thread.sleep((long) delay.calculateDelay());
		// 			for (String product : products) {
		// 				out.collect(product);
		// 			}
		// 		}
		// 	});	
		
		DataStream<String> sink = products
			.map(new ProductToJsonMapper())
			.name("Map: Product to Json");
		
		// Starts transmitting data to the other cluster
		sink.sinkTo(kafkaSink)
			.name("Kafka Sink");

		env.execute("Flink Data Generation");
	}

	private static void loadProperties() {
		Properties properties = new Properties();
		try (InputStream inputStream = DataStreamJob.class.getClassLoader().getResourceAsStream("flink.properties")) {
			properties.load(inputStream);
		} catch (IOException e) {
			System.out.println(e.getMessage());
		}
		KAFKA_BOOTSTRAP_SERVERS = properties.getProperty("KAFKA_BOOTSTRAP_SERVERS");
        CONSUMER_TOPIC = properties.getProperty("CONSUMER_TOPIC");
		CLUSTER_COMMUNICATION_TOPIC = properties.getProperty("CLUSTER_COMMUNICATION_TOPIC");
	}

	private static KafkaSource<String> createKafkaSource(String topic) {
		return KafkaSource.<String>builder()
				.setBootstrapServers(KAFKA_BOOTSTRAP_SERVERS)
				.setTopics(topic)
				.setGroupId("data-generator")
				.setStartingOffsets(OffsetsInitializer.earliest())
				.setValueOnlyDeserializer(new SimpleStringSchema())
				.build();
	}

	private static KafkaSink<String> createLocalKafkaSink() {
		KafkaSink<String> sink = KafkaSink.<String>builder()
				.setBootstrapServers(KAFKA_BOOTSTRAP_SERVERS)
				.setRecordSerializer(KafkaRecordSerializationSchema.builder()
						.setTopic(CLUSTER_COMMUNICATION_TOPIC)
						.setValueSerializationSchema(new SimpleStringSchema())
						.build()
				)
				.setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
				.build();

		return sink;
	}
}
