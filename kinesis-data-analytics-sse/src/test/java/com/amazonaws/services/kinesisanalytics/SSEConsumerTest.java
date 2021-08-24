// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.services.kinesisanalytics;

import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

public class SSEConsumerTest {
    final Logger logger = LoggerFactory.getLogger(SSEConsumerTest.class);

    @Test
    public void testSSE() throws Exception {
        String url = "https://stream.wikimedia.org/v2/stream/recentchange";

        Properties configProps = new Properties();
        configProps.setProperty(SSESource.CONFIG_PROPERTY_URL, url);
        configProps.setProperty(SSESource.CONFIG_PROPERTY_HEADERS, "testheader|testvalue|testheader2|testvalue2");
        configProps.setProperty(SSESource.CONFIG_PROPERTY_READ_TIMEOUT_MS, "0");
        configProps.setProperty(SSESource.CONFIG_PROPERTY_REPORT_MESSAGE_RECEIVED_MS, "5000");
        configProps.setProperty(SSESource.CONFIG_PROPERTY_TYPES, "message");
        SSESource source = new SSESource(configProps, logger);
        Thread t = new Thread(() -> {
            try {
                source.run(new SourceFunction.SourceContext<>() {
                    @Override
                    public void collect(String s) {
                        logger.info("Collected data: " + s);
                    }

                    @Override
                    public void collectWithTimestamp(String s, long l) {
                        logger.info("Collected data with timestamp: " + s);
                    }

                    @Override
                    public void emitWatermark(Watermark watermark) {
                        logger.info("emitWatermark");
                    }

                    @Override
                    public void markAsTemporarilyIdle() {
                        logger.info("markAsTemporarilyIdle");
                    }

                    @Override
                    public Object getCheckpointLock() {
                        logger.info("getCheckpointLock");
                        return null;
                    }

                    @Override
                    public void close() {
                        logger.info("close");
                    }
                });
            } catch (Exception e) {
                logger.error(e.getMessage());
            }
        });
        logger.info("Starting Run");
        t.start();
        logger.info("Waiting");
        for (int x = 1; x <= 8; x++) {
            Thread.sleep(250);
        }
        logger.info("cancel");
        source.cancel();
        logger.info("join");
        t.join();
        logger.info("Done");
    }
}
