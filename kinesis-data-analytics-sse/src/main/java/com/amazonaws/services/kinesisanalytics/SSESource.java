// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.services.kinesisanalytics;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.http2.StreamResetException;
import okhttp3.sse.*;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

public class SSESource extends RichSourceFunction<String> {

    public static final String CONFIG_PROPERTY_URL = "url";
    public static final String CONFIG_PROPERTY_HEADERS = "headers";
    public static final String CONFIG_PROPERTY_TYPES = "types";
    public static final String CONFIG_PROPERTY_READ_TIMEOUT_MS = "readTimeoutMS";
    public static final String CONFIG_PROPERTY_REPORT_MESSAGE_RECEIVED_MS = "reportMessagesReceivedMS";

    private final Properties configProps;
    private volatile boolean isRunning = true;
    private volatile boolean isConnected = false;
    private final Logger logger;
    private int readTimeoutMS = 0;
    private long messagesReceived = 0L;
    private long reportMessagesReceivedMS = 0;

    public SSESource(Properties configProps, Logger logger) {
        this.configProps = configProps;
        this.logger = logger;
    }

    @Override
    public void run(SourceContext<String> sourceContext) throws Exception {
        String url = configProps.getProperty(CONFIG_PROPERTY_URL);
        List<String> collectTypes = null;
        //Allow the end user to change the type of events we collect and send into the kinesis stream
        if (configProps.containsKey(CONFIG_PROPERTY_TYPES)) {
            collectTypes = Arrays.asList(configProps.getProperty(CONFIG_PROPERTY_TYPES).split("\\|"));
        }
        // Allow the end user to adjust the SSE read timeout value in milliseconds. Note in most cases adjusting this to anything but zero will cause the system to not connect.
        if (configProps.containsKey(CONFIG_PROPERTY_READ_TIMEOUT_MS)) {
            readTimeoutMS = Integer.parseInt(configProps.getProperty(CONFIG_PROPERTY_READ_TIMEOUT_MS));
        }
        // Allow the end user to specify how often to report the number of messages received over a given period of time in milliseconds
        if (configProps.containsKey(CONFIG_PROPERTY_REPORT_MESSAGE_RECEIVED_MS)) {
            reportMessagesReceivedMS = Integer.parseInt(configProps.getProperty(CONFIG_PROPERTY_REPORT_MESSAGE_RECEIVED_MS));
        }
        logger.info("SSESource read timeout: " + readTimeoutMS);
        logger.info("SSESource report messages received MS: " + reportMessagesReceivedMS);

        while (isRunning) {
            messagesReceived = 0L; //reset the number of messages received since last connect
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(readTimeoutMS, TimeUnit.MILLISECONDS)
                    .retryOnConnectionFailure(true)
                    .build();
            EventSourceListener listener = new EventSourceSender(sourceContext, logger, collectTypes);
            Request.Builder requestBuilder = new Request.Builder();

            // Allow the end user to specify url headers to send during the initial sse connection
            if (configProps.containsKey(CONFIG_PROPERTY_HEADERS)) {
                String headers = configProps.getProperty(CONFIG_PROPERTY_HEADERS);
                String[] splitHeaders = headers.split("\\|");
                requestBuilder = requestBuilder.headers(Headers.of(splitHeaders));
            }

            // Create a request and connect using the standard headers for SSE endpoints
            Request request = requestBuilder
                    .url(url)
                    .header("Accept-Encoding", "")
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .build();
            logger.info("SSESource Request created to: " + url);
            final EventSource eventSource = EventSources.createFactory(client).newEventSource(request, listener);
            isConnected = true;
            logger.info("SSESource connected");
            try {
                long startTime = System.currentTimeMillis();
                // while we are connected and running we need to hold this thread and report messages received if that option is enabled.
                // SSE events are sent via a callback in another thread
                while (isRunning && isConnected) {
                    Thread.sleep(100);
                    long endTime = System.currentTimeMillis();
                    if (reportMessagesReceivedMS > 0 && (endTime - startTime > reportMessagesReceivedMS)) {
                        logger.info("SSERate received [" + messagesReceived + "] events between [" + startTime + "] and [" + endTime + "]");
                        startTime = endTime;
                    }
                }
            } catch (InterruptedException e) {
                logger.error("Sleep timer interrupted");
            }
            eventSource.cancel();
            logger.info("SSESource Stopping event source");
        }
    }

    @Override
    public void cancel() {
        isRunning = false;
    }

    /***
     * This call will handle the actual SSE events and publish them to the Kinesis Data Streams stream via the collect call
     */
    public final class EventSourceSender extends EventSourceListener {
        final Logger logger;
        final SourceContext<String> sourceContext;
        final List<String> collectTypes;

        public EventSourceSender(SourceContext<String> sourceContext, Logger logger, List<String> collectTypes) {
            this.sourceContext = sourceContext;
            this.logger = logger;
            this.collectTypes = collectTypes;
        }

        /***
         * Callback when the SSE endpoint connection is made. Currently all that is done is to log the event.
         * @param eventSource the event source
         * @param response the response
         */
        @Override public void onOpen(@NotNull EventSource eventSource, @NotNull Response response) {
            logger.info("SSESource open");
        }

        /***
         * For each event received from the SSE endpoint we check if its a type requested and then publish to the Kinesis Data Streams stream via the collect call
         * @param eventSource The event source
         * @param id The id of the event
         * @param type The type of the event which is used to filter
         * @param data The event data
         */
        @Override public void onEvent(@NotNull EventSource eventSource, String id, String type, @NotNull String data) {
            if (collectTypes == null || collectTypes.contains(type)) {
                if (reportMessagesReceivedMS > 0) {
                    messagesReceived++;
                }
                sourceContext.collect(data);
            }
        }

        /***
         * When the connection is closed we receive this even which is currently only logged.
         * @param eventSource The event source
         */
        @Override public void onClosed(@NotNull EventSource eventSource) {
            logger.info("SSESource closed");
        }

        /***
         * If there is any failure we log the error and the stack trace
         * During stream resets with no errors we set the isConnected flag to false to allow the main thread to attempt a re-connect
         * @param eventSource The event source
         * @param t The error object
         * @param response The response
         */
        @Override
        public void onFailure(@NotNull EventSource eventSource, Throwable t, Response response) {
            if (t != null) {
                logger.error("SSESource Error: " + t.getMessage());
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                t.printStackTrace(pw);
                logger.error("SSESource Error Trace: " + sw);
                if (t instanceof StreamResetException && t.getMessage().contains("NO_ERROR")) {
                    isConnected = false;
                }
            }
        }
    }
}
