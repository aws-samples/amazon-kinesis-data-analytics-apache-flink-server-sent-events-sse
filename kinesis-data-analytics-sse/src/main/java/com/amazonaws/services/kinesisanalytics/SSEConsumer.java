package com.amazonaws.services.kinesisanalytics;

import com.amazonaws.services.kinesisanalytics.runtime.KinesisAnalyticsRuntime;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kinesis.FlinkKinesisProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

public class SSEConsumer {
    public static final String PRODUCER_CONFIG_PROPERTIES = "ProducerConfigProperties";
    public static final String OUTPUT_STREAM_PROPERTIES = "OutputStreamProperties";
    public static final String DEFAULT_STREAM = "DefaultStream";
    public static final String SSE_SOURCE_PROPERTIES = "SSESourceProperties";

    private static FlinkKinesisProducer<String> createSinkFromApplicationConfig() throws IOException {
        Map<String, Properties> applicationProperties = KinesisAnalyticsRuntime.getApplicationProperties();
        Properties producerProperties = applicationProperties.get(PRODUCER_CONFIG_PROPERTIES);
        FlinkKinesisProducer<String> sink = new FlinkKinesisProducer<>(new SimpleStringSchema(), producerProperties);
        sink.setDefaultStream(applicationProperties.get(OUTPUT_STREAM_PROPERTIES).getProperty(DEFAULT_STREAM));
        sink.setDefaultPartition("0");
        return sink;
    }

    private static DataStream<String> createSourceFromApplicationProperties(StreamExecutionEnvironment env, Logger logger) throws IOException {
        Map<String, Properties> applicationProperties = KinesisAnalyticsRuntime.getApplicationProperties();
        return env.addSource(new SSESource(applicationProperties.get(SSE_SOURCE_PROPERTIES), logger)).name("SSE Source").uid("SSE Source").setParallelism(1);
    }

    public static void main(String[] args) throws Exception {
        Logger logger = LoggerFactory.getLogger(SSEConsumer.class);
        // set up the streaming execution environment
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        DataStream<String> input = createSourceFromApplicationProperties(env, logger);
        logger.info("SseEventSource Input stream created: " + input.toString());
        input.addSink(createSinkFromApplicationConfig()).name("Kinesis Sink").uid("Kinesis Sink");
        logger.info("SseEventSource Sink Added");
        env.execute("Server Sent Events");
    }
}
