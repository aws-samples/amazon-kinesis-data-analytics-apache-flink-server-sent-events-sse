// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: MIT-0

package com.amazonaws.services.kinesisanalytics;

import software.amazon.awscdk.core.App;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;

import org.junit.jupiter.api.Test;

public class AmazonKinesisDataAnalyticsApacheFlinkServerSentEventsSseCdkTest {
    private final static ObjectMapper JSON =
        new ObjectMapper().configure(SerializationFeature.INDENT_OUTPUT, true);

    @Test
    public void testStack() throws IOException {
        App app = new App();
        new AmazonKinesisDataAnalyticsApacheFlinkServerSentEventsSseCdkStack(app, "TestWithVPC", true);
        new AmazonKinesisDataAnalyticsApacheFlinkServerSentEventsSseCdkStack(app, "testWithoutVPC", false);
    }
}
