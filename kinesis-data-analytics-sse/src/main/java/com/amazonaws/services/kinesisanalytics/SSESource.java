package com.amazonaws.services.kinesisanalytics;

import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.internal.http2.StreamResetException;
import okhttp3.sse.*;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
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

    private Properties configProps;
    private volatile boolean isRunning = true;
    private volatile boolean isConnected = false;
    private Logger logger;
    private int readTimeoutMS = 0;
    private long messagesReceived = 0l;
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
            messagesReceived = 0l; //reset the number of messages received since last connect
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
                while (isRunning && isConnected) {
                    Thread.sleep(100);
                    long endTime = System.currentTimeMillis();
                    if (reportMessagesReceivedMS > 0 && ((endTime - startTime) > reportMessagesReceivedMS)) {
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

    public final class EventSourceSender extends EventSourceListener {
        Logger logger;
        SourceContext<String> sourceContext;
        List<String> collectTypes;

        public EventSourceSender(SourceContext<String> sourceContext, Logger logger, List<String> collectTypes) {
            this.sourceContext = sourceContext;
            this.logger = logger;
            this.collectTypes = collectTypes;
        }

        @Override public void onOpen(EventSource eventSource, Response response) {
            logger.info("SSESource open");
        }

        @Override public void onEvent(EventSource eventSource, String id, String type, String data) {
            if (collectTypes == null || collectTypes.contains(type)) {
                if (reportMessagesReceivedMS > 0) {
                    messagesReceived++;
                }
                sourceContext.collect(data);
            }
        }

        @Override public void onClosed(EventSource eventSource) {
            logger.info("SSESource closed");
        }

        @Override
        public void onFailure(EventSource eventSource, Throwable t, Response response) {
            logger.error("SSESource Error: " + t.getMessage());
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            logger.error("SSESource Error Trace: " + sw.toString());
            if (t instanceof StreamResetException && t.getMessage().contains("NO_ERROR")) {
                isConnected = false;
            }
        }
    }
}
